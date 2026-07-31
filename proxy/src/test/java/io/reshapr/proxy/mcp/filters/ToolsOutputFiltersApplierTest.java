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
package io.reshapr.proxy.mcp.filters;

import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ToolsOutputFilterApplier.
 * @author laurent
 */
class ToolsOutputFiltersApplierTest {

   private static final String FILTERS_ARTIFACT_CONTENT = """
         apiVersion: reshapr.io/v1alpha1
         kind: ToolsOutputFilters
         service:
           name: Test API
           version: '1.0.0'
         filters:
           getUser:
             jsonRetain:
               - /name
               - /email
           listUsers:
             jsonPatches:
               - op: remove
                 path: /password
               - op: remove
                 path: /internalId
           getProfile:
             jsonRetain:
               - /userInfo
           getDetails:
             jsonRetain:
               - /userInfo/name
               - /userInfo/email
           updateUser:
             jsonRetain:
               - /name
             jsonPatches:
               - op: add
                 path: /filtered
                 value: true
         """;

   private static final ObjectMapper MAPPER = new ObjectMapper();

   @Test
   void testApplyRetainFilter() throws Exception {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "{\"name\":\"John\",\"email\":\"john@test.com\",\"password\":\"secret\",\"age\":30}";
      String filtered = applier.applyFilter("getUser", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertTrue(result.has("email"));
      assertFalse(result.has("password"));
      assertFalse(result.has("age"));
   }

   @Test
   void testApplyPatchFilter() throws Exception {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "{\"name\":\"John\",\"email\":\"john@test.com\",\"password\":\"secret\",\"internalId\":\"abc123\"}";
      String filtered = applier.applyFilter("listUsers", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertTrue(result.has("email"));
      assertFalse(result.has("password"));
      assertFalse(result.has("internalId"));
   }

   @Test
   void testRetainEntireSubtree() throws Exception {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "{\"userInfo\":{\"name\":\"John\",\"email\":\"john@test.com\",\"age\":30},\"token\":\"xyz\"}";
      String filtered = applier.applyFilter("getProfile", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("userInfo"));
      assertFalse(result.has("token"));
      assertEquals("John", result.get("userInfo").get("name").asText());
      assertEquals("john@test.com", result.get("userInfo").get("email").asText());
      assertEquals(30, result.get("userInfo").get("age").asInt());
   }

   @Test
   void testRetainNestedPaths() throws Exception {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "{\"userInfo\":{\"name\":\"John\",\"email\":\"john@test.com\",\"age\":30},\"token\":\"xyz\"}";
      String filtered = applier.applyFilter("getDetails", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("userInfo"));
      assertFalse(result.has("token"));
      assertEquals("John", result.get("userInfo").get("name").asText());
      assertEquals("john@test.com", result.get("userInfo").get("email").asText());
      assertFalse(result.get("userInfo").has("age"));
   }

   @Test
   void testRetainOnArray() throws Exception {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "[{\"name\":\"John\",\"email\":\"j@t.com\",\"age\":30},{\"name\":\"Jane\",\"email\":\"ja@t.com\",\"age\":25}]";
      String filtered = applier.applyFilter("getUser", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.isArray());
      assertEquals(2, result.size());
      assertTrue(result.get(0).has("name"));
      assertTrue(result.get(0).has("email"));
      assertFalse(result.get(0).has("age"));
   }

   @Test
   void testRetainOnArrayIndices() throws Exception {
      String filterItem0Name = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              getItem0Name:
                jsonRetain:
                  - /items/0/name
            """;

      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, filterItem0Name);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = """
            {
              "items": [
                {
                  "name": "Item A",
                  "value": 10
                },
                {
                  "name": "Item B",
                  "value": 20
                }
              ]
            }
            """;
      String filtered = applier.applyFilter("getItem0Name", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("items"));
      assertTrue(result.get("items").isArray());
      assertEquals(1, result.get("items").size());
      assertTrue(result.get("items").get(0).has("name"));
      assertFalse(result.get("items").get(0).has("value"));
   }

   @Test
   void testRetainThenPatch() throws Exception {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "{\"name\":\"John\",\"email\":\"john@test.com\",\"password\":\"secret\"}";
      String filtered = applier.applyFilter("updateUser", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertFalse(result.has("email"));
      assertFalse(result.has("password"));
      assertTrue(result.has("filtered"));
      assertTrue(result.get("filtered").asBoolean());
   }

   @Test
   void testNoFilterForTool() {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "{\"name\":\"John\",\"password\":\"secret\"}";
      String filtered = applier.applyFilter("unknownTool", response);

      assertEquals(response, filtered);
   }

   @Test
   void testNonJsonContentPassesThrough() {
      ToolsOutputFiltersApplier applier = buildApplier();

      String response = "This is plain text, not JSON";
      String filtered = applier.applyFilter("getUser", response);

      assertEquals(response, filtered);
   }

   @Test
   void testFailingPatchReturnsOriginalResponse() {
      // Build an applier with a filter that tries to remove a non-existent path.
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              failingTool:
                jsonPatches:
                  - op: remove
                    path: /nonExistent/deeply/nested
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      // Valid JSON but the patch will fail because the path does not exist.
      String response = "{\"name\":\"John\",\"email\":\"john@test.com\"}";
      String filtered = applier.applyFilter("failingTool", response);

      // Original response must be returned unchanged.
      assertEquals(response, filtered);
   }

   @Test
   void testHasFilters() {
      ToolsOutputFiltersApplier applier = buildApplier();
      assertTrue(applier.hasFilters());
   }

   @Test
   void testConvertToToon() {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              getToon:
                convertToToon: true
              getRetainAndToon:
                jsonRetain:
                  - /name
                  - /age
                convertToToon: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      // Test simple conversion to Toon.
      String response = "{\"name\":\"John\",\"age\":30}";
      String filtered = applier.applyFilter("getToon", response);

      // Toon format should not be JSON anymore.
      assertNotEquals(response, filtered);
      assertFalse(filtered.startsWith("{"));

      // Test retain + convertToToon combined.
      String response2 = "{\"name\":\"John\",\"age\":30,\"password\":\"secret\"}";
      String filtered2 = applier.applyFilter("getRetainAndToon", response2);

      // Should not contain password (filtered by retain) and should be in Toon format.
      assertFalse(filtered2.startsWith("{"));
      assertFalse(filtered2.contains("password"));
      assertTrue(filtered2.contains("John"));
   }

   @Test
   void testNoFiltersWhenNoArtifacts() {
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, null, cache);
      assertFalse(applier.hasFilters());
   }

   private ToolsOutputFiltersApplier buildApplier() {
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, FILTERS_ARTIFACT_CONTENT);
      WorkCache cache = new WorkCache(100);
      return new ToolsOutputFiltersApplier(service, List.of(artifact), cache);
   }

   /**
    * Step 7ter: filters are aggregated across ALL attached ToolsOutputFilters artifacts of the same type,
    * not just the first one. A tool declared in the second artifact must have its filter applied.
    */
   @Test
   void filtersAreAggregatedAcrossMultipleArtifacts() {
      String firstArtifact = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              getUser:
                jsonRetain:
                  - /name
            """;
      String secondArtifact = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              getAccount:
                jsonRetain:
                  - /iban
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry a1 = new ArtifactEntry("art-1", "filters-1.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, firstArtifact);
      ArtifactEntry a2 = new ArtifactEntry("art-2", "filters-2.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, secondArtifact);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(a1, a2), cache);

      assertTrue(applier.hasFilters());

      // Filter from the FIRST artifact applies.
      String user = "{\"name\":\"John\",\"password\":\"secret\"}";
      String filteredUser = applier.applyFilter("getUser", user);
      assertTrue(filteredUser.contains("John"));
      assertFalse(filteredUser.contains("password"));

      // Filter from the SECOND artifact also applies (previously ignored by findFirst()).
      String account = "{\"iban\":\"FR76\",\"balance\":1000}";
      String filteredAccount = applier.applyFilter("getAccount", account);
      assertTrue(filteredAccount.contains("FR76"));
      assertFalse(filteredAccount.contains("balance"));
   }

   @Test
   void testCompactRemovesNullFields() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"Alice\",\"middleName\":null,\"age\":30}";
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertTrue(result.has("age"));
      assertFalse(result.has("middleName"));
   }

   @Test
   void testCompactRemovesEmptyStrings() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"Bob\",\"bio\":\"\",\"status\":\"active\"}";
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertTrue(result.has("status"));
      assertFalse(result.has("bio"));
   }

   @Test
   void testCompactRemovesEmptyArrays() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"Alice\",\"roles\":[\"admin\"],\"tags\":[]}";
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertTrue(result.has("roles"));
      assertFalse(result.has("tags"));
   }

   @Test
   void testCompactRemovesEmptyObjects() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"Bob\",\"address\":{}}";
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertFalse(result.has("address"));
   }

   @Test
   void testCompactNestedObjectCompaction() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"user\":{\"name\":\"Alice\",\"middleName\":null,\"bio\":\"\"},\"emptyUser\":{\"note\":\"\"}}";
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("user"));
      assertEquals("Alice", result.get("user").get("name").asText());
      assertFalse(result.get("user").has("middleName"));
      assertFalse(result.get("user").has("bio"));
      // emptyUser became empty after bio was pruned, so emptyUser itself is pruned
      assertFalse(result.has("emptyUser"));
   }

   @Test
   void testCompactArrayCompaction() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "[\"active\",null,\"\",[],{}]";
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.isArray());
      assertEquals(1, result.size());
      assertEquals("active", result.get(0).asText());
   }

   @Test
   void testCompactNestedArraysOfObjects() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactTool:
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = """
            {
              "users": [
                {
                  "name": "Alice",
                  "middleName": null,
                  "tags": []
                },
                {
                  "name": "Bob",
                  "bio": "",
                  "address": {}
                }
              ]
            }
            """;
      String filtered = applier.applyFilter("compactTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("users"));
      JsonNode users = result.get("users");
      assertTrue(users.isArray());
      assertEquals(2, users.size());

      assertEquals("Alice", users.get(0).get("name").asText());
      assertFalse(users.get(0).has("middleName"));
      assertFalse(users.get(0).has("tags"));

      assertEquals("Bob", users.get(1).get("name").asText());
      assertFalse(users.get(1).has("bio"));
      assertFalse(users.get(1).has("address"));
   }

   @Test
   void testCompactAfterJsonPatches() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              patchAndCompact:
                jsonPatches:
                  - op: add
                    path: /addedNull
                    value: null
                compact: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"John\"}";
      String filtered = applier.applyFilter("patchAndCompact", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertFalse(result.has("addedNull")); // Patched null field was pruned by compact
   }

   @Test
   void testCompactBeforeConvertToToon() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              compactAndToon:
                compact: true
                convertToToon: true
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"John\",\"bio\":\"\",\"middleName\":null}";
      String filtered = applier.applyFilter("compactAndToon", response);

      assertFalse(filtered.startsWith("{"));
      assertTrue(filtered.contains("John"));
      assertFalse(filtered.contains("bio"));
      assertFalse(filtered.contains("middleName"));
   }

   @Test
   void testNoCompactLeavesResponseUnchanged() throws Exception {
      String artifactContent = """
            apiVersion: reshapr.io/v1alpha1
            kind: ToolsOutputFilters
            service:
              name: Test API
              version: '1.0.0'
            filters:
              uncompactedTool:
                jsonRetain:
                  - /name
                  - /middleName
                  - /bio
            """;
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry artifact = new ArtifactEntry("art-1", "filters.yaml", null,
            ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS, false, artifactContent);
      WorkCache cache = new WorkCache(100);
      ToolsOutputFiltersApplier applier = new ToolsOutputFiltersApplier(service, List.of(artifact), cache);

      String response = "{\"name\":\"John\",\"middleName\":null,\"bio\":\"\"}";
      String filtered = applier.applyFilter("uncompactedTool", response);

      JsonNode result = MAPPER.readTree(filtered);
      assertTrue(result.has("name"));
      assertTrue(result.has("middleName"));
      assertTrue(result.has("bio"));
      assertTrue(result.get("middleName").isNull());
      assertEquals("", result.get("bio").asText());
   }
}
