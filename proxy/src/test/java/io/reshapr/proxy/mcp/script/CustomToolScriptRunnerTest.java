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
package io.reshapr.proxy.mcp.script;

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.mcp.DeclaredTool;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.state.ElicitationStore;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of {@link CustomToolScriptRunner} and the {@code rs} host API, isolating the
 * JavaScript layer from any backend by overriding {@link ToolCallExecutor#execute}.
 * @author laurent
 */
class CustomToolScriptRunnerTest {

   private static final ObjectMapper MAPPER = new ObjectMapper();

   private static ElicitationStore stubElicitationStore() {
      return new ElicitationStore(null);
   }

   /** An executor that echoes back the called tool and arguments as a JSON content. */
   private static ToolCallExecutor echoExecutor(GatewayRegistry registry) {
      return new ToolCallExecutor(registry, stubElicitationStore(), new UserSecretStore(null), new WorkCache(1000),
            new ProxyService(new SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)), null) {
         @Override
         public ToolCallOutcome executeInternal(ExpositionEntry exposition, String toolName, Map<String, Object> arguments,
                                        Map<String, List<String>> headers) {
            try {
               Map<String, Object> body = new LinkedHashMap<>();
               body.put("tool", toolName);
               body.put("args", arguments);
               return new Success(MAPPER.writeValueAsString(body), false);
            } catch (Exception e) {
               return new Failure(-32603, e.getMessage(), null);
            }
         }
      };
   }

   private static ServiceEntry service() {
      return new ServiceEntry("1", "reshapr", "GitHub GraphQL", "20250917", "GRAPHQL", List.of());
   }

   private static ConfigurationEntry configuration() {
      return new ConfigurationEntry("1", "github-default",
            null, null, null, null, null, null, null);
   }

   private static ExpositionEntry exposition() {
      return new ExpositionEntry("1", "github-default", service(),  configuration(),
            null, List.of());
   }

   private static Map<String, Object> parse(String json) throws Exception {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
   }

   @Test
   void testSynchronousCallTool() throws Exception {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = echoExecutor(registry);

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            List.of(new DeclaredTool(null, "user")));

      String script = """
            const r = rs.callTool('user', { login: input.user });
            return { calledTool: r.content.tool, login: r.content.args.login, ok: r.ok };
            """;

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String result = runner.run(script, Map.of("user", "lbroudoux"), builtins);

      Map<String, Object> parsed = parse(result);
      assertEquals("user", parsed.get("calledTool"));
      assertEquals("lbroudoux", parsed.get("login"));
      assertEquals(Boolean.TRUE, parsed.get("ok"));
   }

   @Test
   void testAsynchronousCallToolAndAwaitPromises() throws Exception {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = echoExecutor(registry);

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            List.of(new DeclaredTool(null, "user")));

      String script = """
            const p1 = rs.callToolAsync('user', { login: input.user });
            const p2 = rs.callToolAsync('user', { login: 'other' });
            const res = rs.awaitPromises([p1, p2]);
            return { first: res[0].content.args.login, second: res[1].content.args.login, count: res.length };
            """;

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String result = runner.run(script, Map.of("user", "lbroudoux"), builtins);

      Map<String, Object> parsed = parse(result);
      assertEquals("lbroudoux", parsed.get("first"));
      assertEquals("other", parsed.get("second"));
      assertEquals(2, ((Number) parsed.get("count")).intValue());
   }

   @Test
   void testCallToolNotInAllowListIsRejected() throws Exception {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = echoExecutor(registry);

      // 'user' is NOT declared in the allow-list.
      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            List.of(new DeclaredTool(null, "repository")));

      String script = """
            const r = rs.callTool('user', { login: input.user });
            return { ok: r.ok, code: r.error ? r.error.code : null };
            """;

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String result = runner.run(script, Map.of("user", "lbroudoux"), builtins);

      Map<String, Object> parsed = parse(result);
      assertEquals(Boolean.FALSE, parsed.get("ok"));
      assertEquals(-32602, ((Number) parsed.get("code")).intValue());
   }

   @Test
   void testBuildFullScriptWrapsPreludeInputAndProcess() {
      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String full = runner.buildFullScript("return { a: 1 };", Map.of("user", "lbroudoux"));

      assertTrue(full.contains("const rs = {"), "Missing rs façade prelude");
      assertTrue(full.contains("\"user\":\"lbroudoux\""), "Missing serialized input");
      assertTrue(full.contains("const input = "), "Missing input constant");
      assertTrue(full.contains("function process() { return JSON.stringify(__process()); }"),
            "Missing process() wrapper");
   }

   // ---------------------------------------------------------------------------------------------
   // End-to-end with the proposed GitHub scripts and a simulated `user` tool response.
   // ---------------------------------------------------------------------------------------------

   @Test
   void testUserOverviewSlimsTheGraphQLPayload() throws Exception {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = gitHubUserExecutor(registry);

      JsonNode customTool = loadCustomTool("user_overview");
      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            declaredToolsOf(customTool));

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String result = runner.run(customTool.get("script").asText(), Map.of("user", "lbroudoux"), builtins);

      Map<String, Object> parsed = parse(result);
      assertEquals("lbroudoux", parsed.get("login"));
      assertEquals("Name of lbroudoux", parsed.get("name"));
      assertEquals("Acme", parsed.get("company"));

      List<?> repos = (List<?>) parsed.get("topRepositories");
      assertEquals(5, repos.size());
      Map<?, ?> firstRepo = (Map<?, ?>) repos.getFirst();
      assertEquals("lbroudoux-repo-1", firstRepo.get("name"));
      assertEquals(10, ((Number) firstRepo.get("stars")).intValue());

      List<?> issues = (List<?>) parsed.get("recentIssues");
      assertEquals(5, issues.size());
      assertEquals("lbroudoux issue 1", ((Map<?, ?>) issues.getFirst()).get("title"));
   }

   @Test
   void testCompareTwoUsersInParallel() throws Exception {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = gitHubUserExecutor(registry);

      JsonNode customTool = loadCustomTool("compare_two_users");
      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            declaredToolsOf(customTool));

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String result = runner.run(customTool.get("script").asText(),
            Map.of("firstUser", "alice", "secondUser", "bob"), builtins);

      Map<String, Object> parsed = parse(result);
      List<?> users = (List<?>) parsed.get("users");
      assertEquals(2, users.size());

      Map<?, ?> first = (Map<?, ?>) users.get(0);
      assertEquals("alice", first.get("login"));
      assertEquals(5, ((Number) first.get("repositoriesSampleSize")).intValue());
      assertEquals(5, ((List<?>) first.get("topRepositoryNames")).size());
      assertEquals("alice-repo-1", ((List<?>) first.get("topRepositoryNames")).getFirst());

      Map<?, ?> second = (Map<?, ?>) users.get(1);
      assertEquals("bob", second.get("login"));
   }

   /** Load a custom tool node from the test YAML resource. */
   private static JsonNode loadCustomTool(String name) throws Exception {
      String yaml = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/script/github-user-script-tools.yaml"),
            StandardCharsets.UTF_8);
      JsonNode root = ObjectMapperFactory.getYamlObjectMapper().readTree(yaml);
      return root.get("customTools").get(name);
   }

   /** Parse the `tools` allow-list from a custom tool node. */
   private static List<DeclaredTool> declaredToolsOf(JsonNode customTool) {
      List<DeclaredTool> declaredTools = new ArrayList<>();
      for (JsonNode toolNode : customTool.get("tools")) {
         JsonNode serviceNode = toolNode.get("service");
         declaredTools.add(new DeclaredTool(serviceNode == null ? null : serviceNode.asText(),
               toolNode.get("tool").asText()));
      }
      return declaredTools;
   }

   /** A simulated executor returning a GraphQL-shaped `user` response honoring the relation sizes. */
   private static ToolCallExecutor gitHubUserExecutor(GatewayRegistry registry) {
      return new ToolCallExecutor(registry, stubElicitationStore(), new UserSecretStore(null), new WorkCache(1000), new ProxyService(new io.reshapr.proxy.secret.SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)), null) {
         @Override
         public ToolCallOutcome executeInternal(ExpositionEntry exposition, String toolName, Map<String, Object> arguments,
                                        Map<String, List<String>> headers) {
            if (!"user".equals(toolName)) {
               return new Failure(-32602, "Unknown tool: " + toolName, null);
            }
            String login = String.valueOf(arguments.get("login"));
            int repoCount = relationSize(arguments, "__relation_repositories", "first");
            int issueCount = relationSize(arguments, "__relation_issues", "last");

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("login", login);
            user.put("name", "Name of " + login);
            user.put("bio", "Bio of " + login);
            user.put("company", "Acme");
            user.put("location", "Paris");

            List<Map<String, Object>> repos = new ArrayList<>();
            for (int i = 1; i <= repoCount; i++) {
               Map<String, Object> repo = new LinkedHashMap<>();
               repo.put("name", login + "-repo-" + i);
               repo.put("description", "Description of repo " + i);
               repo.put("stargazerCount", i * 10);
               repo.put("url", "https://github.com/" + login + "/repo" + i);
               repos.add(repo);
            }
            user.put("repositories", Map.of("nodes", repos));

            List<Map<String, Object>> issues = new ArrayList<>();
            for (int i = 1; i <= issueCount; i++) {
               Map<String, Object> issue = new LinkedHashMap<>();
               issue.put("title", login + " issue " + i);
               issue.put("state", i % 2 == 0 ? "CLOSED" : "OPEN");
               issue.put("url", "https://github.com/" + login + "/issues/" + i);
               issues.add(issue);
            }
            user.put("issues", Map.of("nodes", issues));

            try {
               return new Success(MAPPER.writeValueAsString(Map.of("data", Map.of("user", user))), false);
            } catch (Exception e) {
               return new Failure(-32603, e.getMessage(), null);
            }
         }
      };
   }

   /** Extract a relation paging size (e.g. first/last) from the call arguments, defaulting to 0. */
   private static int relationSize(Map<String, Object> arguments, String relation, String key) {
      if (arguments.get(relation) instanceof Map<?, ?> relationArgs
            && relationArgs.get(key) instanceof Number size) {
         return size.intValue();
      }
      return 0;
   }

   // ---------------------------------------------------------------------------------------------
   // Guard-rails: depth propagation and execution timeout.
   // ---------------------------------------------------------------------------------------------

   /** An executor that echoes back the current script nesting depth. */
   private static ToolCallExecutor depthProbeExecutor(GatewayRegistry registry) {
      return new ToolCallExecutor(registry, stubElicitationStore(), new UserSecretStore(null), new WorkCache(1000), new ProxyService(new io.reshapr.proxy.secret.SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)), null) {
         @Override
         public ToolCallOutcome executeInternal(ExpositionEntry exposition, String toolName, Map<String, Object> arguments,
                                        Map<String, List<String>> headers) {
            try {
               return new Success(MAPPER.writeValueAsString(Map.of("depth", ScriptExecutionContext.currentDepth())), false);
            } catch (Exception e) {
               return new Failure(-32603, e.getMessage(), null);
            }
         }
      };
   }

   @Test
   void testScriptDepthIsPropagatedToSynchronousCalls() {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = depthProbeExecutor(registry);

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            List.of(new DeclaredTool(null, "probe")));

      String script = "const r = rs.callTool('probe', {}); return { depth: r.content.depth };";
      String result = new CustomToolScriptRunner(MAPPER).run(script, Map.of(), builtins, 3);

      assertEquals(3, ((Number) parseQuietly(result).get("depth")).intValue());
   }

   @Test
   void testScriptDepthIsPropagatedToAsynchronousCalls() {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ToolCallExecutor executor = depthProbeExecutor(registry);

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, executor, Map.of(),
            List.of(new DeclaredTool(null, "probe")));

      String script = """
            const p = rs.callToolAsync('probe', {});
            const res = rs.awaitPromises([p]);
            return { depth: res[0].content.depth };
            """;
      String result = new CustomToolScriptRunner(MAPPER).run(script, Map.of(), builtins, 4);

      assertEquals(4, ((Number) parseQuietly(result).get("depth")).intValue());
   }

   @Test
   void testScriptExecutionTimeout() {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();

      // An executor whose call blocks longer than the configured timeout.
      ToolCallExecutor slow = new ToolCallExecutor(registry, stubElicitationStore(), new UserSecretStore(null), new WorkCache(1000),
            new ProxyService(new io.reshapr.proxy.secret.SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)), null) {
         @Override
         public ToolCallOutcome executeInternal(ExpositionEntry exp, String toolName, Map<String, Object> arguments,
                                        Map<String, List<String>> headers) {
            try {
               Thread.sleep(500);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               return new Failure(-32603, "interrupted", null);
            }
            return new Success("{}", false);
         }
      };

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry, slow, Map.of(),
            List.of(new DeclaredTool(null, "slow")));

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER, 150);
      String script = "const r = rs.callTool('slow', {}); return { ok: r.ok };";

      assertThrows(CustomToolScriptRunner.CustomToolScriptException.class,
            () -> runner.run(script, Map.of(), builtins));
   }

   private static Map<String, Object> parseQuietly(String json) {
      try {
         return parse(json);
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   // ---------------------------------------------------------------------------------------------
   // Failure signaling: throw and rs.fail.
   // ---------------------------------------------------------------------------------------------

   @Test
   void testCompilationErrorIsConciseAndFlagged() {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry,
            gitHubUserExecutor(registry), Map.of(), List.of());

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      // A syntax error (empty expression) makes the assembled script fail to compile.
      CustomToolScriptRunner.CustomToolScriptException ex = assertThrows(
            CustomToolScriptRunner.CustomToolScriptException.class,
            () -> runner.run("const x = ; return { oops: true };", Map.of(), builtins));

      assertTrue(ex.isCompilationError(), "Should be flagged as a compilation error");
      // The surfaced content must not dump the whole assembled script (prelude/builtins bridge).
      assertFalse(ex.errorContent().contains("globalThis.__rs"),
            "Compilation error content should not include the assembled script: " + ex.errorContent());
      assertFalse(ex.errorContent().contains("Failed to compile JS code:"),
            "Compilation error content should be trimmed: " + ex.errorContent());
      assertTrue(ex.errorContent().length() < 500,
            "Compilation error content should be concise: " + ex.errorContent());
   }

   @Test
   void testExtractCompilationDetailKeepsOnlyStderr() {
      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      String raw = "Failed to compile JS code:\nglobalThis.__rs = {};\nfunction process() {}\n"
            + "stderr: SyntaxError: unexpected token in expression: ';'\nstdout: ";

      String detail = runner.extractCompilationDetail(raw);

      assertEquals("SyntaxError: unexpected token in expression: ';'", detail);
   }

   @Test
   void testScriptThrowIsSurfacedAsErrorContent() {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry,
            gitHubUserExecutor(registry), Map.of(), List.of());

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      CustomToolScriptRunner.CustomToolScriptException ex = assertThrows(
            CustomToolScriptRunner.CustomToolScriptException.class,
            () -> runner.run("throw new Error('boom happened');", Map.of(), builtins));

      assertTrue(ex.errorContent().contains("boom happened"), "Unexpected error content: " + ex.errorContent());
   }

   @Test
   void testRsFailProducesStructuredError() throws Exception {
      GatewayRegistry registry = new GatewayRegistry();
      ExpositionEntry exposition = exposition();
      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, registry,
            gitHubUserExecutor(registry), Map.of(), List.of());

      CustomToolScriptRunner runner = new CustomToolScriptRunner(MAPPER);
      CustomToolScriptRunner.CustomToolScriptException ex = assertThrows(
            CustomToolScriptRunner.CustomToolScriptException.class,
            () -> runner.run("rs.fail('quota exceeded', { retryAfter: 30 });", Map.of(), builtins));

      Map<String, Object> content = parse(ex.errorContent());
      assertEquals("quota exceeded", content.get("message"));
      assertEquals(30, ((Number) ((Map<?, ?>) content.get("data")).get("retryAfter")).intValue());
   }
}


