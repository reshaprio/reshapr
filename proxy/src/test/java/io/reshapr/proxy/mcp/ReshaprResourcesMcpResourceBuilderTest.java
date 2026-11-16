/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.mcp;

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

class ReshaprResourcesMcpResourceBuilderTest {

   @Test
   void testResourceTemplates() {
      String uri = "file:///project/src/main.rs?mode=raw";
      String uriTemplate = "file:///project/src/{path}?mode=raw";

      String uriPattern = uriTemplate.replace("/", "\\/")
            .replace("?", "\\?")
            .replaceAll("\\{(.*)\\}", "(.*)");

      System.err.println("uriPattern: " + uriPattern);

      boolean matches = uri.matches(uriPattern);
      System.err.println("matches: " + matches);

      // Extract group 1.
      if (matches) {
         String extractedPath = uri.replaceAll(uriPattern, "$1");
         System.err.println("extractedPath: " + extractedPath);
      }
   }

   @Test
   void testRemoteContentUsesResolvedRemoteSecretOrFallsBackToUnauthenticated() {
      String yaml = """
            apiVersion: reshapr.io/v1alpha1
            kind: Resources
            service:
              name: UI Service
              version: '1.0'
            resources:
              'ui://app/protected':
                name: protected-ui
                remoteContent: https://ui.internal.example/protected.html
                remoteSecret: ui-secret
              'ui://app/public':
                name: public-ui
                remoteContent: https://ui.example.com/public.html
              'ui://app/missing-secret':
                name: missing-secret-ui
                remoteContent: https://ui.internal.example/other.html
                remoteSecret: unknown-secret
            """;

      SecretEntry uiSecret = new SecretEntry("ui-secret", null, null, "ui-tok", null, null, false, null);
      SecretEntry backendSecret = new SecretEntry("backend-secret", null, null, "api-tok", null, null, false, null);

      ProxyService proxyService = Mockito.mock(ProxyService.class);
      Mockito.when(proxyService.callBackend(any(), any(URI.class), eq("GET"), any(), any(), any()))
            .thenReturn(new BackendResponse(200, "<html>ok</html>".getBytes(StandardCharsets.UTF_8), Map.of()));

      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "UI Service", "1.0", "REST", List.of());
      ArtifactEntry artifact = new ArtifactEntry(
            "art-res", "resources.yaml", null, ArtifactEntryType.RESHAPR_RESOURCES, false, yaml, List.of(uiSecret));
      ConfigurationEntry config = new ConfigurationEntry(
            "cfg-1", "plan", "http://backend.local", null, List.of(), List.of(), null, null, backendSecret);

      ReshaprResourcesMcpResourceBuilder builder = new ReshaprResourcesMcpResourceBuilder(
            service, List.of(artifact), new WorkCache(100), ObjectMapperFactory.getYamlObjectMapper(), proxyService);

      var protectedContents = builder.readResource(new McpSchema.ReadResourceRequest("ui://app/protected"), config);
      assertNotNull(protectedContents);
      Mockito.verify(proxyService).callBackend(
            eq(config), eq(URI.create("https://ui.internal.example/protected.html")), eq("GET"), any(), isNull(), eq(uiSecret));

      var publicContents = builder.readResource(new McpSchema.ReadResourceRequest("ui://app/public"), config);
      assertNotNull(publicContents);
      Mockito.verify(proxyService).callBackend(
            eq(config), eq(URI.create("https://ui.example.com/public.html")), eq("GET"), any(), isNull(), isNull());

      var missingSecretContents = builder.readResource(new McpSchema.ReadResourceRequest("ui://app/missing-secret"), config);
      assertNotNull(missingSecretContents);
      Mockito.verify(proxyService).callBackend(
            eq(config), eq(URI.create("https://ui.internal.example/other.html")), eq("GET"), any(), isNull(), isNull());
   }
}
