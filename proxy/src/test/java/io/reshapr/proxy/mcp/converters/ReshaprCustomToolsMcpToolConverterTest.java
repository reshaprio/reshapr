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
package io.reshapr.proxy.mcp.converters;

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.ParserOptions;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is a test case for ReshaprCustomToolsMcpToolConverter.
 * @author laurent
 */
class ReshaprCustomToolsMcpToolConverterTest {

   @Test
   void testGraphQLAPIWithCustomTools() throws Exception {
      String specification = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/github-api.graphql"),
            StandardCharsets.UTF_8);
      ArtifactEntry artifactEntry = new ArtifactEntry("1", "github-api.graphql",
            "GRAPHQL", ArtifactEntryType.GRAPHQL_SCHEMA, true, specification);

      List<OperationEntry> operations = List.of(
            new OperationEntry("user", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "User"),
            new OperationEntry("repository", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "Repository")
      );
      ServiceEntry serviceEntry = new ServiceEntry("1", "reshapr", "GitHub GraphQL",
            "20250917", "GRAPHQL", operations);

      String customTools = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/converters/github-api-custom-tools.yaml"),
            StandardCharsets.UTF_8);
      ArtifactEntry attachedArtifactEntry = new ArtifactEntry("2", "github-api-custom-tools.yaml",
            "CUSTOM_TOOLS", ArtifactEntryType.RESHAPR_CUSTOM_TOOLS, false, customTools);

      ConfigurationEntry configuration = new ConfigurationEntry("1", "github-default",
            null, null, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("1", "github-default", serviceEntry,  configuration,
            artifactEntry, List.of(attachedArtifactEntry));

      // Create ObjectMapper with correct options.
      ParserOptions.setDefaultParserOptions(
            ParserOptions.getDefaultParserOptions().transform(
                  opts -> opts.maxCharacters(100000000).maxTokens(100000)));
      ObjectMapper objectMapper = new ObjectMapper();

      // Build the wrapper converter.
      WorkCache workCache = new WorkCache(1000);
      GraphQLMcpToolConverter converter = new GraphQLMcpToolConverter(exposition, workCache, objectMapper,
            new ProxyService(new SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)));

      ReshaprCustomToolsMcpToolConverter customConverter = new ReshaprCustomToolsMcpToolConverter(exposition,
            workCache, converter);

      // Now call methods and assert we get expected results.
      List<OperationEntry> operationEntries = customConverter.getAvailableOperations(serviceEntry);
      assertNotNull(operationEntries);
      assertEquals(2, operationEntries.size());

      OperationEntry customOperationEntry = null;
      for (OperationEntry operation : operationEntries) {
         assertTrue("get_user_with_latest_followers".equals(operation.name())
               || "repository".equals(operation.name()));

         if ("get_user_with_latest_followers".equals(operation.name())) {
            McpSchema.JsonSchema schema = customConverter.getInputSchema(operation);
            String schemaStr = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            assertTrue(schemaStr.contains("user"));
            assertTrue(schemaStr.contains("The GitHub login of the user to fetch"));
            assertFalse(schemaStr.contains("__relation_followers"));
            customOperationEntry = operation;
         }
      }

      assertNotNull(customOperationEntry);

      String toolCallRequest = """
            {
               "jsonrpc": "2.0",
               "method": "tools/call",
               "params": {
                  "name": "get_user_with_latest_followers",
                  "arguments": {
                     "user": "lbroudoux",
                     "object": {
                        "someField": "someValue"
                     }
                  }
               }
            }
            """;

      McpSchema.JSONRPCRequest mcpRequest = objectMapper.readValue(toolCallRequest, McpSchema.JSONRPCRequest.class);
      McpSchema.SimpleRequest customToolRequest = objectMapper.convertValue(mcpRequest.params(),
            new TypeReference<McpSchema.SimpleRequest>() {
            });

      Map<String, Object> customArgumentsValues = new HashMap<>();
      customConverter.completeCustomArgumentsMap(customArgumentsValues, customToolRequest.arguments(), "");

      assertEquals(2, customArgumentsValues.size());
      assertEquals("lbroudoux", customArgumentsValues.get("user"));
      assertEquals("someValue", customArgumentsValues.get("object.someField"));

      ObjectMapper yamlMapper = ObjectMapperFactory.getYamlObjectMapper();
      JsonNode customToolsNode = yamlMapper.readTree(customTools).get("customTools");
      JsonNode customToolNode = customToolsNode.get("get_user_with_latest_followers");

      Map<String, Object> targetArgumentsTemplate = customConverter.getCustomToolTargetArguments(customOperationEntry, customToolNode);
      Map<String, Object> targetArguments = customConverter.buildTargetArguments(targetArgumentsTemplate, customArgumentsValues);

      assertEquals(3, targetArguments.size());
      assertEquals("lbroudoux", targetArguments.get("login"));
      assertEquals("{size=32}", targetArguments.get("__relation_avatarUrl").toString());
      assertEquals("{last=10}", targetArguments.get("__relation_followers").toString());
   }

   @Test
   void testDeclarativeCustomToolWithUnknownTargetThrows() throws Exception {
      String specification = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/github-api.graphql"),
            StandardCharsets.UTF_8);
      ArtifactEntry artifactEntry = new ArtifactEntry("1", "github-api.graphql",
            "GRAPHQL", ArtifactEntryType.GRAPHQL_SCHEMA, true, specification);

      List<OperationEntry> operations = List.of(
            new OperationEntry("user", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "User"),
            new OperationEntry("repository", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "Repository")
      );
      ServiceEntry serviceEntry = new ServiceEntry("1", "reshapr", "GitHub GraphQL",
            "20250917", "GRAPHQL", operations);

      // A declarative custom tool referencing a target tool that does not exist on the service.
      String customTools = """
            apiVersion: reshapr.io/v1alpha1
            kind: CustomTools
            customTools:
              broken_tool:
                tool: doesNotExist
                description: References a missing tool
                input:
                  type: object
                  properties:
                    user:
                      type: string
                  required:
                    - user
                arguments:
                  login: ${user}
            """;
      ArtifactEntry attachedArtifactEntry = new ArtifactEntry("2", "broken-custom-tools.yaml",
            "CUSTOM_TOOLS", ArtifactEntryType.RESHAPR_CUSTOM_TOOLS, false, customTools);

      ConfigurationEntry configuration = new ConfigurationEntry("1", "github-default",
            null, null, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("1", "github-default", serviceEntry, configuration,
            artifactEntry, List.of(attachedArtifactEntry));

      ParserOptions.setDefaultParserOptions(
            ParserOptions.getDefaultParserOptions().transform(
                  opts -> opts.maxCharacters(100000000).maxTokens(100000)));
      ObjectMapper objectMapper = new ObjectMapper();

      WorkCache workCache = new WorkCache(1000);
      GraphQLMcpToolConverter converter = new GraphQLMcpToolConverter(exposition, workCache, objectMapper,
            new ProxyService(new SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)));
      ReshaprCustomToolsMcpToolConverter customConverter = new ReshaprCustomToolsMcpToolConverter(exposition,
            workCache, converter);

      OperationEntry brokenOperation = new OperationEntry("broken_tool", "QUERY", null, null, null);
      McpSchema.SimpleRequest request = new McpSchema.SimpleRequest("broken_tool", Map.of("user", "lbroudoux"));

      CustomToolResolutionException exception = assertThrows(CustomToolResolutionException.class,
            () -> customConverter.getCallResponse(brokenOperation, configuration, request, new HashMap<>()));
      assertTrue(exception.getMessage().contains("broken_tool"));
      assertTrue(exception.getMessage().contains("doesNotExist"));
   }

   // ---------------------------------------------------------------------------------------------
   // getExposedOperations - composition of the config plan restriction with custom tools reshaping
   // ---------------------------------------------------------------------------------------------

   @Test
   void testExposedOperationsWithNoRestrictionExposesCustomToolAndHidesTarget() throws Exception {
      // No include/exclude: the declarative custom tool replaces its target 'user'; 'repository' stays.
      ServiceEntry service = githubService();
      ConfigurationEntry config = new ConfigurationEntry("1", "github-default",
            null, null, List.of(), List.of(), null, null, null);
      ReshaprCustomToolsMcpToolConverter converter = buildGithubConverter(config, service);

      List<String> exposed = names(converter.getExposedOperations(service, config));
      assertEquals(2, exposed.size());
      assertTrue(exposed.contains("get_user_with_latest_followers")); // custom tool replaces 'user'
      assertTrue(exposed.contains("repository"));
      assertFalse(exposed.contains("user"));                          // target hidden
   }

   @Test
   void testExposedOperationsIncludeTargetKeepsCustomTool() throws Exception {
      // includedOperations restricts to 'user' (the custom tool target): the custom tool must remain
      // exposed even though its own name is not a service operation. This is the regression this fix targets.
      ServiceEntry service = githubService();
      ConfigurationEntry config = new ConfigurationEntry("1", "github-default",
            null, null, List.of(), List.of("user"), null, null, null);
      ReshaprCustomToolsMcpToolConverter converter = buildGithubConverter(config, service);

      List<String> exposed = names(converter.getExposedOperations(service, config));
      assertEquals(List.of("get_user_with_latest_followers"), exposed);
   }

   @Test
   void testExposedOperationsIncludeWithoutTargetHidesCustomTool() throws Exception {
      // includedOperations restricts to 'repository' only: the custom tool target 'user' is not exposed,
      // so the custom tool that reshapes it is hidden as well; only 'repository' remains.
      ServiceEntry service = githubService();
      ConfigurationEntry config = new ConfigurationEntry("1", "github-default",
            null, null, List.of(), List.of("repository"), null, null, null);
      ReshaprCustomToolsMcpToolConverter converter = buildGithubConverter(config, service);

      List<String> exposed = names(converter.getExposedOperations(service, config));
      assertEquals(List.of("repository"), exposed);
   }

   @Test
   void testExposedOperationsExcludeTargetHidesCustomTool() throws Exception {
      // excludedOperations hides 'user': the declarative custom tool built on it must not leak it back.
      ServiceEntry service = githubService();
      ConfigurationEntry config = new ConfigurationEntry("1", "github-default",
            null, null, List.of("user"), List.of(), null, null, null);
      ReshaprCustomToolsMcpToolConverter converter = buildGithubConverter(config, service);

      List<String> exposed = names(converter.getExposedOperations(service, config));
      assertEquals(List.of("repository"), exposed);
      assertFalse(exposed.contains("get_user_with_latest_followers"));
   }

   // ---------------------------------------------------------------------------------------------
   // getResolvableOperations - internal (script) call resolution ignores the exposure/reshaping
   // ---------------------------------------------------------------------------------------------

   @Test
   void testResolvableOperationsAlwaysIncludeRawTargetAndCustomTools() throws Exception {
      // A declarative custom tool hides its target 'user' from the exposed surface, but a script must
      // still be able to resolve the raw 'user' operation directly (gated by its allow-list, not exposure).
      ServiceEntry service = githubService();
      ConfigurationEntry config = new ConfigurationEntry("1", "github-default",
            null, null, List.of(), List.of(), null, null, null);
      ReshaprCustomToolsMcpToolConverter converter = buildGithubConverter(config, service);

      // Sanity check: 'user' is indeed hidden from the exposed surface (replaced by the custom tool).
      assertFalse(names(converter.getExposedOperations(service, config)).contains("user"));

      List<String> resolvable = names(converter.getResolvableOperations(service));
      assertTrue(resolvable.contains("user"));                            // raw target still resolvable
      assertTrue(resolvable.contains("repository"));
      assertTrue(resolvable.contains("get_user_with_latest_followers")); // custom tool also resolvable
   }

   // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

   /** The GitHub GraphQL service exposing two operations: 'user' and 'repository'. */
   private static ServiceEntry githubService() {
      List<OperationEntry> operations = List.of(
            new OperationEntry("user", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "User"),
            new OperationEntry("repository", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "Repository"));
      return new ServiceEntry("1", "reshapr", "GitHub GraphQL", "20250917", "GRAPHQL", operations);
   }

   /**
    * Build a custom-tools converter over the GitHub GraphQL service with one declarative custom tool
    * 'get_user_with_latest_followers' targeting 'user', for the given configuration plan.
    */
   private static ReshaprCustomToolsMcpToolConverter buildGithubConverter(ConfigurationEntry configuration,
         ServiceEntry serviceEntry) throws Exception {
      String specification = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/github-api.graphql"), StandardCharsets.UTF_8);
      ArtifactEntry artifactEntry = new ArtifactEntry("1", "github-api.graphql",
            "GRAPHQL", ArtifactEntryType.GRAPHQL_SCHEMA, true, specification);
      String customTools = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/converters/github-api-custom-tools.yaml"),
            StandardCharsets.UTF_8);
      ArtifactEntry attachedArtifactEntry = new ArtifactEntry("2", "github-api-custom-tools.yaml",
            "CUSTOM_TOOLS", ArtifactEntryType.RESHAPR_CUSTOM_TOOLS, false, customTools);
      ExpositionEntry exposition = new ExpositionEntry("1", "github-default", serviceEntry, configuration,
            artifactEntry, List.of(attachedArtifactEntry));

      ParserOptions.setDefaultParserOptions(
            ParserOptions.getDefaultParserOptions().transform(
                  opts -> opts.maxCharacters(100000000).maxTokens(100000)));
      WorkCache workCache = new WorkCache(1000);
      GraphQLMcpToolConverter converter = new GraphQLMcpToolConverter(exposition, workCache, new ObjectMapper(),
            new ProxyService(new SecretReferenceResolver(List.of()), new UserSecretStore(null)));
      return new ReshaprCustomToolsMcpToolConverter(exposition, workCache, converter);
   }

   private static List<String> names(List<OperationEntry> operations) {
      return operations.stream().map(OperationEntry::name).toList();
   }
}
