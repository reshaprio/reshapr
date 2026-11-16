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

import io.reshapr.proxy.TestResources;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * This is a unit test for the {@link ReshaprSkillsMcpSkillBuilder} class, which implements the {@link McpSkillBuilder} interface.
 * @author laurent
 */
public class ReshaprSkillsMcpSkillBuilderTest {

   private static final String SKILL_MD = """
         ---
         name: pdf-processing
         description: Extract and fill PDF documents
         license: Apache-2.0
         ---
         # PDF processing
         See references/FORMS.md
         """;

   private static final String ARTIFACT = """
         apiVersion: reshapr.io/v1alpha1
         kind: Skills
         service:
           name: Test
           version: '1.0'
         skills:
           pdf-processing:
             path: acme
             remoteContent: http://skills.local/pdf-processing
             files:
               - references/FORMS.md
               - scripts/extract.py
               - scripts/lib/util.py
         """;

   private McpSkillBuilder provider;
   private ConfigurationEntry configuration;

   @BeforeEach
   void setUp() {
      ProxyService proxyService = Mockito.mock(ProxyService.class);
      configuration = Mockito.mock(ConfigurationEntry.class);
      Map<String, String> remote = Map.of(
            "http://skills.local/pdf-processing/SKILL.md", SKILL_MD,
            "http://skills.local/pdf-processing/references/FORMS.md", "# Forms",
            "http://skills.local/pdf-processing/scripts/extract.py", "print('x')",
            "http://skills.local/pdf-processing/scripts/lib/util.py", "pass");
      Mockito.when(proxyService.callBackend(any(), any(URI.class), eq("GET"), any(), any(), any())).thenAnswer(invocation -> {
         String content = remote.get(invocation.<URI>getArgument(1).toString());
         return content == null ? new BackendResponse(404, new byte[0], Map.of())
               : new BackendResponse(200, content.getBytes(StandardCharsets.UTF_8), Map.of());
      });

      ServiceEntry service = new ServiceEntry("svc", "org", "Test", "1.0", "REST", List.of());
      ArtifactEntry artifact = new ArtifactEntry("art", "skills", null, ArtifactEntryType.RESHAPR_SKILLS, false, ARTIFACT);
      provider = new ReshaprSkillsMcpSkillBuilder(service, List.of(artifact), new WorkCache(100), proxyService);
   }

   @Test
   void testListAndGetSkills() {
      assertTrue(provider.hasSkills());

      List<McpSchema.SkillEntry> skills = provider.listSkills(configuration);
      assertEquals(1, skills.size());
      McpSchema.SkillEntry skill = skills.get(0);
      assertEquals("skill://acme/pdf-processing/SKILL.md", skill.uri());
      assertEquals("pdf-processing", skill.frontmatter().get("name"));
      assertEquals("Apache-2.0", skill.frontmatter().get("license"));
      assertEquals(4, skill.resources().size());
      assertEquals(SKILL_MD.getBytes(StandardCharsets.UTF_8).length, skill.resources().get(0).size());
      assertTrue(skill.resources().get(0).digest().matches("sha256:[0-9a-f]{64}"));

      assertNotNull(provider.getSkill("skill://acme/pdf-processing/SKILL.md", configuration));
      assertNull(provider.getSkill("skill://acme/unknown/SKILL.md", configuration));
   }

   @Test
   void testReadResource() {
      var contents = provider.readResource("skill://acme/pdf-processing/references/FORMS.md", configuration);
      assertNotNull(contents);
      var text = assertInstanceOf(McpSchema.TextResourceContents.class, contents.get(0));
      assertEquals("# Forms", text.text());
      assertEquals("text/markdown", text.mimeType());

      assertNull(provider.readResource("skill://acme/pdf-processing/secret.txt", configuration));
   }

   @Test
   void testReadDirectory() {
      var root = provider.readDirectory("skill://acme/pdf-processing", configuration);
      assertEquals(List.of("SKILL.md", "references", "scripts"), root.stream().map(McpSchema.Resource::name).toList());

      var scripts = provider.readDirectory("skill://acme/pdf-processing/scripts", configuration);
      assertEquals(List.of("extract.py", "lib"), scripts.stream().map(McpSchema.Resource::name).toList());
      assertEquals(McpSchema.MIME_TYPE_DIRECTORY, scripts.get(1).mimeType());

      assertNull(provider.readDirectory("skill://acme/pdf-processing/nope", configuration));
   }

   @Test
   void testFrontmatterMismatchSkipsSkill() {
      assertNull(ReshaprSkillsMcpSkillBuilder.parseFrontmatter("# no frontmatter"));
   }

   @Test
   void testRemoteSecretPassedToProxyServiceAndUnresolvedFallsBackToNull() {
      String yaml = TestResources.readString("io/reshapr/proxy/mcp/skills-remote-secret.yaml");

      SecretEntry skillsToken = new SecretEntry("skills-token", null, null, "tok-xyz", null, null, false, null);
      SecretEntry backendSecret = new SecretEntry("backend-secret", null, null, "backend-tok", null, null, false, null);
      ConfigurationEntry config = new ConfigurationEntry(
            "cfg", "plan", "http://backend.local", null, List.of(), List.of(), null, null, backendSecret);

      ProxyService mockProxy = Mockito.mock(ProxyService.class);
      Mockito.when(mockProxy.callBackend(any(), any(URI.class), eq("GET"), any(), any(), any())).thenAnswer(invocation -> {
         String url = invocation.<URI>getArgument(1).toString();
         String name = url.contains("pdf-processing") ? "pdf-processing"
               : url.contains("public") ? "public-skill" : "broken-secret-skill";
         String md = """
               ---
               name: %s
               description: Test skill
               ---
               body
               """.formatted(name);
         return new BackendResponse(200, md.getBytes(StandardCharsets.UTF_8), Map.of());
      });

      ServiceEntry svc = new ServiceEntry("svc-sec", "org", "Test", "1.0", "REST", List.of());
      ArtifactEntry artifact = new ArtifactEntry(
            "art-sec", "skills", null, ArtifactEntryType.RESHAPR_SKILLS, false, yaml, List.of(skillsToken));
      ReshaprSkillsMcpSkillBuilder skillBuilder = new ReshaprSkillsMcpSkillBuilder(
            svc, List.of(artifact), new WorkCache(100), mockProxy);

      List<McpSchema.SkillEntry> skills = skillBuilder.listSkills(config);
      assertEquals(3, skills.size());

      Mockito.verify(mockProxy).callBackend(
            eq(config), eq(URI.create("http://skills.local/pdf-processing/SKILL.md")), eq("GET"), any(), isNull(), eq(skillsToken));
      Mockito.verify(mockProxy).callBackend(
            eq(config), eq(URI.create("http://skills.local/public/SKILL.md")), eq("GET"), any(), isNull(), isNull());
      Mockito.verify(mockProxy).callBackend(
            eq(config), eq(URI.create("http://skills.local/broken/SKILL.md")), eq("GET"), any(), isNull(), isNull());
   }
}
