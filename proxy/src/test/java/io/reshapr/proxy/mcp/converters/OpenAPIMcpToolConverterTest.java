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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This is a test case for OpenAPIMcpToolConverter.
 * @author laurent
 */
class OpenAPIMcpToolConverterTest {

   @Test
   void testOpenAPIWIthRecurseRefConversion() throws Exception {
      String specification = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/trade-api-3.0.1-openapi.yaml"),
            StandardCharsets.UTF_8);

      ArtifactEntry artifactEntry = new ArtifactEntry("1", "trade-api-3.0.1-openapi.yaml",
            "GRPC", ArtifactEntryType.OPEN_API_SPEC, true, specification);

      List<OperationEntry> operations = List.of(
//            new OperationEntry("GET /customers", "GET", null, null, null),
//            new OperationEntry("GET /customers/{customerId}", "GET", null, null, null),
//            new OperationEntry("GET /accounts", "GET", null, null, null),
//            new OperationEntry("GET /accounts/{accountId}", "GET", null, null, null),
//            new OperationEntry("GET /orders", "GET", null, null, null),
            new OperationEntry("POST /orders", "POST", null, null, null)
      );

      ServiceEntry serviceEntry = new ServiceEntry("1", "reshapr", "Trade API",
      "3.0.1", "REST", operations);

      ConfigurationEntry configurationEntry = new ConfigurationEntry("1", "Trade-API-default",
            null, null, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("1", "Trade-API-default", serviceEntry,  configurationEntry,
            artifactEntry, List.of());

      ObjectMapper objectMapper = new ObjectMapper();

      OpenAPIMcpToolConverter converter = new OpenAPIMcpToolConverter(exposition, new WorkCache(1000),
            objectMapper, new ProxyService(new SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)));

      for (OperationEntry operation : operations) {
         McpSchema.JsonSchema schema = converter.getInputSchema(operation);
         if ("POST /orders".equals(operation.name())) {
            // The underlyingFinancialInstrument on FinancialInstrument is a recursive $ref
            // and should be removed by the converter.
            String schemaStr = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            assertFalse(schemaStr.contains("underlyingFinancialInstrument"));
         }
      }
   }

   /**
    * The work cache is keyed by {@code artifact.id()}, not by the exposition. Two converters
    * built for two distinct expositions (different service context) but referencing the very same artifact
    * (same id and content) must share the parsed-spec cache entry, i.e. the spec is parsed only once and the
    * second converter reuses the already cached {@code JsonNode} instead of re-parsing.
    */
   @Test
   void testSameArtifactIsSharedAcrossExpositions() {
      String spec = """
            {
               "openapi":"3.0.0",
               "info":{
                  "title":"t",
                  "version":"1"
               },
               "paths":{
                  "/ping":{
                     "get":{
                        "responses":{
                           "200":{
                              "description":"ok"
                           }
                        }
                     }
                  }
               }
            }
            """;

      WorkCache cache = new WorkCache(1000);
      ObjectMapper objectMapper = new ObjectMapper();
      ProxyService proxyService = new ProxyService(new SecretReferenceResolver(List.of()), new UserSecretStore(null));
      OperationEntry op = new OperationEntry("GET /ping", "GET", null, null, null);

      // Exposition #1 (service context A) referencing artifact "shared-art".
      ArtifactEntry artifact1 = new ArtifactEntry("shared-art", "spec.json", "REST",
            ArtifactEntryType.OPEN_API_SPEC, true, spec);
      ServiceEntry serviceA = new ServiceEntry("svc-A", "acme", "API", "1.0.0", "REST", List.of(op));
      ConfigurationEntry configurationA = new ConfigurationEntry("1", "API-default",
            null, null, null, null, null, null, null);
      ExpositionEntry expositionA = new ExpositionEntry("1", "API-default", serviceA,  configurationA,
            artifact1, List.of());

      OpenAPIMcpToolConverter converterA = new OpenAPIMcpToolConverter(expositionA, cache, objectMapper, proxyService);
      converterA.getInputSchema(op);

      // The parsed spec is now cached under the artifact id (not the exposition/service).
      Object cachedSchema = cache.get("shared-art", "oapimcptc-schema");
      assertNotNull(cachedSchema, "spec should be cached under the artifact id");

      // Exposition #2 (different service context B) referencing the SAME artifact id and content.
      ArtifactEntry artifact2 = new ArtifactEntry("shared-art", "spec.json", "REST",
            ArtifactEntryType.OPEN_API_SPEC, true, spec);
      ServiceEntry serviceB = new ServiceEntry("svc-B", "acme", "API", "2.0.0", "REST", List.of(op));
      ConfigurationEntry configurationB = new ConfigurationEntry("2", "API-default",
            null, null, null, null, null, null, null);
      ExpositionEntry expositionB = new ExpositionEntry("2", "API-default", serviceB,  configurationB,
            artifact2, List.of());

      OpenAPIMcpToolConverter converterB = new OpenAPIMcpToolConverter(expositionB, cache, objectMapper, proxyService);
      converterB.getInputSchema(op);

      // Same cached instance is reused: converter B did not re-parse (no cache overwrite happened).
      assertSame(cachedSchema, cache.get("shared-art", "oapimcptc-schema"),
            "the second exposition must reuse the shared cache entry keyed by artifact id");
   }

   /**
    * Parameters may be declared at the path item level, shared by all the verbs of that path. They must be
    * merged into the input schema, operation level parameters overriding path item ones on name + location.
    */
   @Test
   void testPathItemLevelParametersAreMerged() {
      String spec = """
            openapi: 3.1.0
            info:
              title: Repro API
              version: 1.0.0
            paths:
              '/widgets/{widgetId}':
                parameters:
                  - name: widgetId
                    in: path
                    required: true
                    description: The widget identifier
                    schema:
                      type: string
                  - name: verbose
                    in: query
                    required: false
                    schema:
                      type: string
                get:
                  operationId: get-widget
                  responses:
                    '200':
                      description: OK
                put:
                  operationId: put-widget
                  parameters:
                    - name: verbose
                      in: query
                      required: true
                      schema:
                        type: boolean
                  responses:
                    '200':
                      description: OK
            """;

      OperationEntry getOperation = new OperationEntry("GET /widgets/{widgetId}", "GET", null, null, null);
      OperationEntry putOperation = new OperationEntry("PUT /widgets/{widgetId}", "PUT", null, null, null);

      ArtifactEntry artifactEntry = new ArtifactEntry("path-item-params", "spec.yaml", "REST",
            ArtifactEntryType.OPEN_API_SPEC, true, spec);
      ServiceEntry serviceEntry = new ServiceEntry("svc", "acme", "Repro API", "1.0.0", "REST",
            List.of(getOperation, putOperation));
      ConfigurationEntry configurationEntry = new ConfigurationEntry("1", "Repro-API-default",
            null, null, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("1", "Repro-API-default", serviceEntry, configurationEntry,
            artifactEntry, List.of());

      ObjectMapper objectMapper = new ObjectMapper();
      OpenAPIMcpToolConverter converter = new OpenAPIMcpToolConverter(exposition, new WorkCache(1000),
            objectMapper, new ProxyService(new SecretReferenceResolver(List.of()), new UserSecretStore(null)));

      // Operation without its own parameters only inherits the path item level ones.
      McpSchema.JsonSchema getSchema = converter.getInputSchema(getOperation);
      assertNotNull(getSchema);
      assertTrue(getSchema.properties().containsKey("widgetId"));
      assertTrue(getSchema.properties().containsKey("verbose"));
      assertTrue(getSchema.required().contains("widgetId"));
      assertFalse(getSchema.required().contains("verbose"));

      // Operation level parameter overrides the path item one having same name and location.
      McpSchema.JsonSchema putSchema = converter.getInputSchema(putOperation);
      assertNotNull(putSchema);
      assertTrue(putSchema.properties().containsKey("widgetId"));
      assertTrue(putSchema.required().contains("verbose"));
      assertEquals(2, putSchema.properties().size());
      assertEquals("boolean", ((java.util.Map<?, ?>) putSchema.properties().get("verbose")).get("type"));
   }

   @Test
   void testLegacyBracketParameterIsAliasedAndRestored() throws Exception {
      OperationEntry operation = new OperationEntry("GET /legacy", "GET", null, null, null);
      AtomicReference<URI> backendUri = new AtomicReference<>();
      TestConverter testConverter = testConverter(operation, backendUri);

      McpSchema.JsonSchema schema = testConverter.converter().getInputSchema(operation);
      assertTrue(schema.properties().containsKey("filter_equals"));
      assertTrue(schema.properties().containsKey("filter_equals_2"));
      assertFalse(schema.properties().containsKey("filter[equals]"));

      Map<String, Object> arguments = new HashMap<>();
      arguments.put("filter_equals_2", "shoes");
      arguments.put("filter_equals", "boots");
      testConverter.converter().getCallResponse(operation, testConverter.configuration(),
            new McpSchema.SimpleRequest("list", arguments), new HashMap<>());

      assertNotNull(backendUri.get());
      assertTrue(backendUri.get().getRawQuery().contains("filter%5Bequals%5D=shoes"));
      assertTrue(backendUri.get().getRawQuery().contains("filter_equals=boots"));
   }

   @Test
   void testDeepObjectAndFormArraySerializationWithReferencedSchema() throws Exception {
      OperationEntry operation = new OperationEntry("GET /products", "GET", null, null, null);
      AtomicReference<URI> backendUri = new AtomicReference<>();
      TestConverter testConverter = testConverter(operation, backendUri);

      McpSchema.JsonSchema schema = testConverter.converter().getInputSchema(operation);
      @SuppressWarnings("unchecked")
      Map<String, Object> filter = (Map<String, Object>) schema.properties().get("filter");
      assertEquals("object", filter.get("type"));
      assertEquals(List.of("shoes", "bags"), ((Map<?, ?>) ((Map<?, ?>) filter.get("properties"))
            .get("equals")).get("enum"));

      Map<String, Object> arguments = new HashMap<>();
      arguments.put("filter", Map.of("equals", "shoes", "exists", true));
      arguments.put("labels", List.of("sale", "clearance"));
      testConverter.converter().getCallResponse(operation, testConverter.configuration(),
            new McpSchema.SimpleRequest("list", arguments), new HashMap<>());

      assertNotNull(backendUri.get());
      String query = backendUri.get().getRawQuery();
      assertTrue(query.contains("filter%5Bequals%5D=shoes"));
      assertTrue(query.contains("filter%5Bexists%5D=true"));
      assertTrue(query.contains("labels=sale") && query.contains("labels=clearance"));
   }

   private TestConverter testConverter(OperationEntry operation, AtomicReference<URI> backendUri) throws Exception {
      String specification = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/query-parameter-serialization.yaml"),
            StandardCharsets.UTF_8);
      ConfigurationEntry configuration = new ConfigurationEntry("1", "default", "http://backend.example.com",
            null, null, null, null, null, null);
      ProxyService proxyService = mock(ProxyService.class);
      when(proxyService.callBackend(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
         backendUri.set(invocation.getArgument(1));
         return new io.reshapr.proxy.proxy.BackendResponse(200, "[]".getBytes(), Map.of());
      });
      ArtifactEntry artifact = new ArtifactEntry("query-parameters", "query-parameter-serialization.yaml", "REST",
            ArtifactEntryType.OPEN_API_SPEC, true, specification);
      ServiceEntry service = new ServiceEntry("svc", "acme", "Query parameters", "1.0", "REST", List.of(operation));
      ExpositionEntry exposition = new ExpositionEntry("1", "default", service, configuration, artifact, List.of());
      return new TestConverter(new OpenAPIMcpToolConverter(exposition, new WorkCache(1000), new ObjectMapper(), proxyService),
            configuration);
   }

   private record TestConverter(OpenAPIMcpToolConverter converter, ConfigurationEntry configuration) {
   }
}
