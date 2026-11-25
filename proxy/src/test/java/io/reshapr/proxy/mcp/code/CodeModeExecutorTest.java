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
package io.reshapr.proxy.mcp.code;

import io.reshapr.proxy.mcp.DeclaredTool;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.mcp.state.ElicitationStore;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.registry.ToolExposureMode;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of {@link CodeModeExecutor}: the three meta-tools are exercised against a stub tool
 * converter, and {@code execute_code} really runs in the QuickJS sandbox with a {@link ToolCallExecutor}
 * that echoes back the calls instead of reaching a backend.
 * @author laurent
 */
class CodeModeExecutorTest {

   private static final ObjectMapper MAPPER = new ObjectMapper();

   private static final CodeModeLimits LIMITS = new CodeModeLimits(10_000L, 10, 5, 2048, 1000, 5000);

   /** A converter exposing three tools with trivial input schemas. */
   private static McpToolConverter stubConverter() {
      return stubConverter(operation -> null);
   }

   /** Same stub, with a controllable {@code getDeclaredTools} so a tool can act as a scripted custom tool. */
   private static McpToolConverter stubConverter(Function<OperationEntry, List<DeclaredTool>> declaredTools) {
      return new McpToolConverter() {
         @Override
         public List<DeclaredTool> getDeclaredTools(OperationEntry operation) {
            return declaredTools.apply(operation);
         }

         @Override
         public String getToolDescription(OperationEntry operation) {
            return switch (operation.name()) {
               case "listIssues" -> "List the issues of a repository.";
               case "getIssue" -> "Get a single issue by number.";
               default -> "Search across the repositories.";
            };
         }

         @Override
         public McpSchema.JsonSchema getInputSchema(OperationEntry operation) {
            return new McpSchema.JsonSchema("object",
                  Map.of("owner", Map.of("type", "string")), List.of("owner"), false);
         }

         @Override
         public Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration,
                                         McpSchema.SimpleRequest request, Map<String, List<String>> headers) {
            return new Response("{}", false);
         }

         @Override
         public Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request,
                                                 Map<String, List<String>> headers) {
            return null;
         }
      };
   }

   /** An executor echoing back the called tool and its arguments, so no backend is involved. */
   private static ToolCallExecutor echoExecutor(GatewayRegistry registry) {
      return new ToolCallExecutor(registry, new ElicitationStore(null), new UserSecretStore(null),
            new WorkCache(1000),
            new ProxyService(new SecretReferenceResolver(List.of()), new UserSecretStore(null)), null, null) {
         @Override
         public ToolCallOutcome executeInternal(ExpositionEntry exposition, String toolName,
                                                Map<String, Object> arguments, Map<String, List<String>> headers) {
            try {
               Map<String, Object> body = new LinkedHashMap<>();
               body.put("tool", toolName);
               body.put("args", arguments);
               return new Success(MAPPER.writeValueAsString(body), false);
            } catch (Exception e) {
               return new Failure(McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage(), null);
            }
         }
      };
   }

   private static ExpositionEntry exposition(List<String> operations, List<String> included) {
      ServiceEntry service = new ServiceEntry("1", "reshapr", "GitHub", "v1", "REST",
            operations.stream().map(name -> new OperationEntry(name, null, null, null, null)).toList());
      ConfigurationEntry configuration = new ConfigurationEntry("c1", "github-default", "http://backend", null,
            List.of(), included, null, null, null, false, null, null, ToolExposureMode.CODE);
      return new ExpositionEntry("e1", "github", service, configuration, null, List.of());
   }

   private static CodeModeExecutor codeMode(ExpositionEntry exposition) {
      return codeMode(exposition, stubConverter());
   }

   private static CodeModeExecutor codeMode(ExpositionEntry exposition, McpToolConverter converter) {
      GatewayRegistry registry = new GatewayRegistry();
      registry.addExposition(exposition);
      return new CodeModeExecutor(exposition, converter, echoExecutor(registry), registry, LIMITS, MAPPER);
   }

   private static CodeModeExecutor defaultCodeMode() {
      return codeMode(exposition(List.of("listIssues", "getIssue", "searchRepositories"), List.of()));
   }

   private static Map<String, Object> parse(String json) throws Exception {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
   }

   private static String successContent(ToolCallExecutor.ToolCallOutcome outcome) {
      ToolCallExecutor.Success success = assertInstanceOf(ToolCallExecutor.Success.class, outcome);
      return success.content();
   }

   // ---------------------------------------------------------------------------------------------
   // exposed surface
   // ---------------------------------------------------------------------------------------------

   @Test
   void testExposedToolsHonorTheConfigurationPlan() {
      CodeModeExecutor codeMode = codeMode(
            exposition(List.of("listIssues", "getIssue", "searchRepositories"), List.of("listIssues")));

      assertEquals(List.of("listIssues"), codeMode.exposedTools().stream().map(ExposedTool::name).toList());
   }

   @Test
   void testMetaToolDefinitionsAreTheThreeCodeModeTools() {
      List<String> names = defaultCodeMode().metaToolDefinitions().stream().map(McpSchema.Tool::name).toList();

      assertEquals(List.of(CodeModeTools.SEARCH_TOOLS, CodeModeTools.GET_API_TYPES, CodeModeTools.EXECUTE_CODE),
            names);
   }

   // ---------------------------------------------------------------------------------------------
   // search_tools
   // ---------------------------------------------------------------------------------------------

   @Test
   @SuppressWarnings("unchecked")
   void testSearchToolsScoresNameAndDescriptionMatches() throws Exception {
      Map<String, Object> result = parse(successContent(
            defaultCodeMode().call(CodeModeTools.SEARCH_TOOLS, Map.of("query", "issue number"), Map.of())));

      List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
      assertEquals(2, tools.size());
      // Both match 'issue' by name and description, but only 'getIssue' also matches 'number'.
      assertEquals("getIssue", tools.getFirst().get("name"));
      assertEquals("listIssues", tools.get(1).get("name"));
      assertEquals(3, result.get("exposed"));
      assertTrue(tools.getFirst().containsKey("description"));
      assertFalse(tools.getFirst().containsKey("inputSchema"));
   }

   @Test
   @SuppressWarnings("unchecked")
   void testSearchToolsDropsTheToolsThatDoNotMatch() throws Exception {
      Map<String, Object> result = parse(successContent(
            defaultCodeMode().call(CodeModeTools.SEARCH_TOOLS, Map.of("query", "repositories"), Map.of())));

      List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
      assertEquals(1, tools.size());
      assertEquals("searchRepositories", tools.getFirst().get("name"));
   }

   @Test
   @SuppressWarnings("unchecked")
   void testSearchToolsWithoutQueryReturnsEverything() throws Exception {
      Map<String, Object> result = parse(successContent(
            defaultCodeMode().call(CodeModeTools.SEARCH_TOOLS, Map.of(), Map.of())));

      assertEquals(3, ((List<Map<String, Object>>) result.get("tools")).size());
      assertEquals(3, result.get("matched"));
   }

   @Test
   @SuppressWarnings("unchecked")
   void testSearchToolsHonorsDetailAndLimit() throws Exception {
      Map<String, Object> result = parse(successContent(defaultCodeMode().call(CodeModeTools.SEARCH_TOOLS,
            Map.of("detail", CodeModeTools.DETAIL_SCHEMA, "limit", 1), Map.of())));

      List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
      assertEquals(1, tools.size());
      assertTrue(tools.getFirst().containsKey("inputSchema"));
      assertEquals(3, result.get("matched"));
      assertTrue(result.containsKey("hint"));
   }

   @Test
   @SuppressWarnings("unchecked")
   void testSearchToolsWithNameDetailOmitsDescriptions() throws Exception {
      Map<String, Object> result = parse(successContent(defaultCodeMode().call(CodeModeTools.SEARCH_TOOLS,
            Map.of("query", "issue", "detail", CodeModeTools.DETAIL_NAME), Map.of())));

      List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
      assertFalse(tools.getFirst().containsKey("description"));
   }

   // ---------------------------------------------------------------------------------------------
   // get_api_types
   // ---------------------------------------------------------------------------------------------

   @Test
   void testGetApiTypesReturnsTheWholeSurfaceByDefault() {
      String types = successContent(defaultCodeMode().call(CodeModeTools.GET_API_TYPES, Map.of(), Map.of()));

      assertTrue(types.contains("listIssues(input:"));
      assertTrue(types.contains("getIssue(input:"));
      assertTrue(types.contains("searchRepositories(input:"));
   }

   @Test
   void testGetApiTypesCanBeNarrowedToSomeTools() {
      String types = successContent(defaultCodeMode().call(CodeModeTools.GET_API_TYPES,
            Map.of("tools", List.of("getIssue")), Map.of()));

      assertTrue(types.contains("getIssue(input:"));
      assertFalse(types.contains("listIssues(input:"));
   }

   @Test
   void testGetApiTypesRejectsUnknownTools() {
      ToolCallExecutor.ToolCallOutcome outcome = defaultCodeMode().call(CodeModeTools.GET_API_TYPES,
            Map.of("tools", List.of("getIssue", "deleteEverything")), Map.of());

      ToolCallExecutor.Failure failure = assertInstanceOf(ToolCallExecutor.Failure.class, outcome);
      assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, failure.code());
      assertTrue(failure.message().contains("deleteEverything"));
   }

   // ---------------------------------------------------------------------------------------------
   // execute_code
   // ---------------------------------------------------------------------------------------------

   @Test
   void testExecuteCodeDrivesTheApiBinding() throws Exception {
      String code = """
            const res = api.listIssues({ owner: 'acme' });
            return { tool: res.content.tool, owner: res.content.args.owner, ok: res.ok };
            """;

      Map<String, Object> result = parse(successContent(
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE, Map.of("code", code), Map.of())));

      assertEquals("listIssues", result.get("tool"));
      assertEquals("acme", result.get("owner"));
      assertEquals(Boolean.TRUE, result.get("ok"));
   }

   @Test
   void testExecuteCodeSupportsLoopsOverSeveralTools() throws Exception {
      String code = """
            const names = [];
            for (const owner of ['a', 'b', 'c']) {
              names.push(api.getIssue({ owner: owner }).content.args.owner);
            }
            return { owners: names, viaRs: rs.callTool('searchRepositories', {}).content.tool };
            """;

      Map<String, Object> result = parse(successContent(
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE, Map.of("code", code), Map.of())));

      assertEquals(List.of("a", "b", "c"), result.get("owners"));
      assertEquals("searchRepositories", result.get("viaRs"));
   }

   @Test
   void testExecuteCodeCannotReachAToolThePlanHides() throws Exception {
      CodeModeExecutor codeMode = codeMode(
            exposition(List.of("listIssues", "deleteRepository"), List.of("listIssues")));
      String code = "return rs.callTool('deleteRepository', {});";

      Map<String, Object> result = parse(successContent(
            codeMode.call(CodeModeTools.EXECUTE_CODE, Map.of("code", code), Map.of())));

      assertEquals(Boolean.FALSE, result.get("ok"));
      assertTrue(result.get("error").toString().contains("allow-list"));
   }

   @Test
   void testExecuteCodeHasNoApiBindingForAHiddenTool() throws Exception {
      CodeModeExecutor codeMode = codeMode(
            exposition(List.of("listIssues", "deleteRepository"), List.of("listIssues")));
      String code = "return { bound: typeof api.deleteRepository };";

      Map<String, Object> result = parse(successContent(
            codeMode.call(CodeModeTools.EXECUTE_CODE, Map.of("code", code), Map.of())));

      assertEquals("undefined", result.get("bound"));
   }

   @Test
   void testExecuteCodeSurfacesScriptFailuresAsToolErrors() {
      ToolCallExecutor.Success success = assertInstanceOf(ToolCallExecutor.Success.class,
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE,
                  Map.of("code", "rs.fail('nope', { reason: 'test' });"), Map.of()));

      assertTrue(success.isFault());
      assertTrue(success.content().contains("nope"));
   }

   @Test
   void testExecuteCodeSurfacesCompilationErrors() {
      ToolCallExecutor.Success success = assertInstanceOf(ToolCallExecutor.Success.class,
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE, Map.of("code", "return ;;;;("), Map.of()));

      assertTrue(success.isFault());
      assertTrue(success.content().contains("failed to compile"));
   }

   @Test
   void testExecuteCodeRequiresSomeCode() {
      ToolCallExecutor.Failure failure = assertInstanceOf(ToolCallExecutor.Failure.class,
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE, Map.of("code", "  "), Map.of()));

      assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, failure.code());
   }

   @Test
   void testExecuteCodeRejectsOversizedCode() {
      String code = "return " + "'x'.repeat(1) + ".repeat(200) + "'end';";

      ToolCallExecutor.Failure failure = assertInstanceOf(ToolCallExecutor.Failure.class,
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE, Map.of("code", code), Map.of()));

      assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, failure.code());
      assertTrue(failure.message().contains("maximum size"));
   }

   @Test
   void testExecuteCodeRejectsOversizedResults() {
      ToolCallExecutor.Success success = assertInstanceOf(ToolCallExecutor.Success.class,
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE,
                  Map.of("code", "return 'x'.repeat(6000);"), Map.of()));

      assertTrue(success.isFault());
      assertTrue(success.content().contains("characters budget"));
   }

   @Test
   void testExecuteCodeEnforcesTheMaximumNumberOfToolCalls() throws Exception {
      String code = """
            let refused = null;
            for (let i = 0; i < 12; i++) {
              const res = api.getIssue({ owner: 'a' });
              if (!res.ok) { refused = res.error.message; break; }
            }
            return { refused: refused };
            """;

      Map<String, Object> result = parse(successContent(
            defaultCodeMode().call(CodeModeTools.EXECUTE_CODE, Map.of("code", code), Map.of())));

      assertTrue(result.get("refused").toString().contains("Maximum number of tool calls"));
   }

   // ---------------------------------------------------------------------------------------------
   // reachable closure (elicitation pre-flight)
   // ---------------------------------------------------------------------------------------------

   @Test
   void testReachableToolsIsTheExposedSurfaceWhenNothingIsDeclared() {
      List<DeclaredTool> reachable = defaultCodeMode().reachableTools();

      // No tool declares anything, so a snippet can only reach its own exposition.
      assertEquals(List.of(new DeclaredTool(null, "listIssues"), new DeclaredTool(null, "getIssue"),
            new DeclaredTool(null, "searchRepositories")), reachable);
      assertTrue(reachable.stream().allMatch(DeclaredTool::isSameService));
   }

   @Test
   void testReachableToolsIncludesWhatAnExposedToolDeclares() {
      // 'getIssue' behaves as a scripted custom tool reaching another service, whose exposition carries its
      // own backend secret: the pre-flight must see it even though no snippet has been submitted yet.
      ExpositionEntry exposition = exposition(List.of("listIssues", "getIssue"), List.of());
      CodeModeExecutor codeMode = codeMode(exposition, stubConverter(operation ->
            "getIssue".equals(operation.name()) ? List.of(new DeclaredTool("billing:v1", "getInvoice")) : null));

      assertEquals(List.of(new DeclaredTool(null, "listIssues"), new DeclaredTool(null, "getIssue"),
            new DeclaredTool("billing:v1", "getInvoice")), codeMode.reachableTools());
   }

   @Test
   void testReachableToolsStopsAtTheToolsThePlanHides() {
      // 'searchRepositories' is excluded from the plan, so a snippet can never trigger its declared call.
      // Pre-flighting it anyway would make the user connect a service the script cannot even reach.
      ExpositionEntry exposition =
            exposition(List.of("listIssues", "searchRepositories"), List.of("listIssues"));
      CodeModeExecutor codeMode = codeMode(exposition, stubConverter(operation ->
            "searchRepositories".equals(operation.name())
                  ? List.of(new DeclaredTool("billing:v1", "getInvoice")) : null));

      assertEquals(List.of(new DeclaredTool(null, "listIssues")), codeMode.reachableTools());
   }

   @Test
   void testReachableToolsDeduplicatesASharedTarget() {
      // Two custom tools fanning out to the same target must not yield two elicitations for one secret.
      ExpositionEntry exposition = exposition(List.of("listIssues", "getIssue"), List.of());
      CodeModeExecutor codeMode = codeMode(exposition,
            stubConverter(operation -> List.of(new DeclaredTool("billing:v1", "getInvoice"))));

      assertEquals(List.of(new DeclaredTool(null, "listIssues"), new DeclaredTool("billing:v1", "getInvoice"),
            new DeclaredTool(null, "getIssue")), codeMode.reachableTools());
   }

   @Test
   void testCallRejectsAnUnknownMetaTool() {
      assertInstanceOf(ToolCallExecutor.Failure.class, defaultCodeMode().call("nope", Map.of(), Map.of()));
   }
}
