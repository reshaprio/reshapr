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

import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.ElicitationStore;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for {@link ToolCallExecutor}.
 * @author laurent
 */
class ToolCallExecutorTest {

   /** An ElicitationStore stub that does not touch any cache. */
   private static ElicitationStore stubElicitationStore() {
      return new ElicitationStore(null) {
         @Override
         public String initializeElicitation(String sessionId, String organizationId, String backendEndpoint,
                                             SecretEntry secretEntry) {
            return "elicit-123";
         }
      };
   }

   private static ToolCallExecutor newExecutor(GatewayRegistry registry, ElicitationStore store) {
      ToolCallExecutor executor = new ToolCallExecutor(registry, store, new UserSecretStore(null), new WorkCache(1000),
            new ProxyService(new SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)), null, null);
      executor.fqdns = List.of("localhost:7777");
      return executor;
   }

   private static OperationEntry op(String name) {
      return new OperationEntry(name, null, null, null, null);
   }

   /** Register a service + configuration as a single (unnamed) exposition in the registry. */
   private static void seedExposition(GatewayRegistry registry, ServiceEntry service, ConfigurationEntry config) {
      registry.addExposition(new ExpositionEntry(config.id(), null, service, config, null, List.of()));
   }

   // ---------------------------------------------------------------------------------------------
   // isExposedOperation
   // ---------------------------------------------------------------------------------------------

   @Test
   void testIsExposedOperationWithIncludeList() {
      ConfigurationEntry config = new ConfigurationEntry("c1", "cfg", "http://b", null,
            List.of(), List.of("user"), null, null, null);

      assertTrue(ToolCallExecutor.isExposedOperation(config, op("user")));
      assertFalse(ToolCallExecutor.isExposedOperation(config, op("repository")));
   }

   @Test
   void testIsExposedOperationWithExcludeList() {
      ConfigurationEntry config = new ConfigurationEntry("c1", "cfg", "http://b", null,
            List.of("repository"), List.of(), null, null, null);

      assertTrue(ToolCallExecutor.isExposedOperation(config, op("user")));
      assertFalse(ToolCallExecutor.isExposedOperation(config, op("repository")));
   }

   @Test
   void testIsExposedOperationByDefault() {
      ConfigurationEntry config = new ConfigurationEntry("c1", "cfg", "http://b", null,
            List.of(), List.of(), null, null, null);

      assertTrue(ToolCallExecutor.isExposedOperation(config, op("anything")));
   }

   // ---------------------------------------------------------------------------------------------
   // execute - elicitation handling
   // ---------------------------------------------------------------------------------------------

   @Test
   void testExecuteReturnsFailureWhenStatelessAndNoUserIdentity() throws Exception {
      ServiceEntry service = new ServiceEntry("1", "reshapr", "GitHub GraphQL", "20250917", "GRAPHQL",
            List.of(op("user")));
      SecretEntry secret = new SecretEntry("s", null, null, null, null, null, true, null);
      ConfigurationEntry config = new ConfigurationEntry("c1", "cfg", "http://backend", null,
            List.of(), List.of(), null, null, secret);

      GatewayRegistry registry = new GatewayRegistry();
      seedExposition(registry, service, config);

      ToolCallExecutor executor = newExecutor(registry, stubElicitationStore());

      // Stateless mode (no MCP session) without an OAuth identity (issuer null) -> no user key.
      MethodHandlingInfo info = new MethodHandlingInfo("127.0.0.1", null, "user1", null, null);
      ToolCallExecutor.ToolCallOutcome outcome = ScopedValue
            .where(MethodHandlingContext.METHOD_HANDLING_INFO, info)
            .call(() -> executor.execute(service, "user", Map.of(), Map.of()));

      ToolCallExecutor.Failure failure = assertInstanceOf(ToolCallExecutor.Failure.class, outcome);
      assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, failure.code());
   }

   @Test
   void testExecuteReturnsElicitationRequiredWhenSecretValueMissing() throws Exception {
      ServiceEntry service = new ServiceEntry("1", "reshapr", "GitHub GraphQL", "20250917", "GRAPHQL",
            List.of(op("user")));
      SecretEntry secret = new SecretEntry("s", null, null, null, null, null, true, null);
      ConfigurationEntry config = new ConfigurationEntry("c1", "cfg", "http://backend", null,
            List.of(), List.of(), null, null, secret);

      GatewayRegistry registry = new GatewayRegistry();
      seedExposition(registry, service, config);

      ToolCallExecutor executor = newExecutor(registry, stubElicitationStore());

      // Bind a handling info with a session but no resolved secret value.
      SessionInfo session = new SessionInfo("sess-1", service.id(), "2025-06-18");
      MethodHandlingInfo info = new MethodHandlingInfo("127.0.0.1", session, "user1", null, null);
      ToolCallExecutor.ToolCallOutcome outcome = ScopedValue
            .where(MethodHandlingContext.METHOD_HANDLING_INFO, info)
            .call(() -> executor.execute(service, "user", Map.of(), Map.of()));

      ToolCallExecutor.ElicitationRequired elicitation = assertInstanceOf(
            ToolCallExecutor.ElicitationRequired.class, outcome);
      assertEquals(1, elicitation.elicitations().size());
      McpSchema.URLElicitation url = elicitation.elicitations().getFirst();
      assertEquals("elicit-123", url.elicitationId());
      assertTrue(url.url().contains("/elicitation/form?elicitationId=elicit-123"),
            "Unexpected elicitation URL: " + url.url());
      assertNotNull(url.message());
   }

   // ---------------------------------------------------------------------------------------------
   // execute - operation resolution
   // ---------------------------------------------------------------------------------------------

   @Test
   void testExecuteReturnsFailureForUnknownTool() {
      ServiceEntry service = new ServiceEntry("1", "reshapr", "GitHub GraphQL", "20250917", "GRAPHQL",
            List.of(op("user")));
      // No backend secret, so the elicitation pre-check is skipped and no session is required.
      ConfigurationEntry config = new ConfigurationEntry("c1", "cfg", "http://backend", null,
            List.of(), List.of(), null, null, null);

      GatewayRegistry registry = new GatewayRegistry();
      seedExposition(registry, service, config);

      ToolCallExecutor executor = newExecutor(registry, stubElicitationStore());

      ToolCallExecutor.ToolCallOutcome outcome = executor.execute(service, "doesNotExist", Map.of(), Map.of());

      ToolCallExecutor.Failure failure = assertInstanceOf(ToolCallExecutor.Failure.class, outcome);
      assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, failure.code());
      assertEquals("Unknown tool: doesNotExist", failure.message());
   }

   // ---------------------------------------------------------------------------------------------
   // preflightToolsElicitation - same-service resolves against the current exposition
   // ---------------------------------------------------------------------------------------------

   @Test
   void testPreflightUsesCurrentExpositionConfigNotElected() throws Exception {
      ServiceEntry service = new ServiceEntry("1", "reshapr", "GitHub GraphQL", "20250917", "GRAPHQL",
            List.of(op("user")));

      // Current (non-elected) exposition c1: its backend secret requires elicitation.
      SecretEntry secret = new SecretEntry("s", null, null, null, null, null, true, null);
      ConfigurationEntry currentConfig = new ConfigurationEntry("c1", "premium", "http://backend", null,
            List.of(), List.of(), null, null, secret);
      // Elected exposition c2 (higher TSID, so it is the elected one): no backend secret at all.
      ConfigurationEntry electedConfig = new ConfigurationEntry("c2", "default", "http://backend", null,
            List.of(), List.of(), null, null, null);

      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry current = new ExpositionEntry("c1", null, service, currentConfig, null, List.of());
      registry.addExposition(current);
      registry.addExposition(new ExpositionEntry("c2", null, service, electedConfig, null, List.of()));

      ToolCallExecutor executor = newExecutor(registry, stubElicitationStore());

      // A session exists but the secret value is not resolved yet.
      SessionInfo session = new SessionInfo("sess-1", service.id(), "2025-06-18");
      MethodHandlingInfo info = new MethodHandlingInfo("127.0.0.1", session, "user1", null, null);

      ToolCallExecutor.ToolCallOutcome outcome = ScopedValue
            .where(MethodHandlingContext.METHOD_HANDLING_INFO, info)
            .call(() -> executor.preflightToolsElicitation(current, List.of(new DeclaredTool(null, "user"))));

      // Elicitation is required because the CURRENT exposition (c1) carries the secret, even though the
      // ELECTED exposition (c2) has none. This proves the preflight uses the current exposition's
      // configuration rather than the elected one.
      ToolCallExecutor.ElicitationRequired elicitation = assertInstanceOf(
            ToolCallExecutor.ElicitationRequired.class, outcome);
      assertEquals(1, elicitation.elicitations().size());
   }
}

