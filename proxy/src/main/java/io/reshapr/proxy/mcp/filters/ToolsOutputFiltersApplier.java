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

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flipkart.zjsonpatch.JsonPatch;
import dev.toonformat.jtoon.JToon;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies output filters defined in a ToolsOutputFilters artifact to tool call responses.
 * This slims down API payloads before they reach LLMs by retaining specific branches
 * and/or applying JSON Patch (RFC 6902) operations.
 * @author laurent
 */
public class ToolsOutputFiltersApplier {

   private static final Logger logger = Logger.getLogger(ToolsOutputFiltersApplier.class);

   private static final String CACHE_KEYS_PREFIX = "tof-";

   private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.getYamlObjectMapper();
   private static final ObjectMapper JSON_MAPPER = ObjectMapperFactory.getJsonObjectMapper();

   private final ServiceEntry service;
   private final List<ArtifactEntry> attachedArtifacts;
   private final WorkCache workCache;

   /**
    * Creates a ToolsOutputFilterApplier for the given service and attached artifacts.
    * @param service the service entry
    * @param attachedArtifacts the list of attached artifacts, or null if none
    * @param workCache the work cache to use for caching
    */
   public ToolsOutputFiltersApplier(ServiceEntry service, @Nullable List<ArtifactEntry> attachedArtifacts,
                                    WorkCache workCache) {
      this.service = service;
      this.attachedArtifacts = attachedArtifacts;
      this.workCache = workCache;
   }

   /**
    * Apply the output filter (if any) for the given tool name on the response content.
    * @param toolName the name of the tool that was called
    * @param responseContent the raw response content string
    * @return the filtered response content, or the original content if no filter applies
    */
   public String applyFilter(String toolName, String responseContent) {
      JsonNode filterNode = getFilterForTool(toolName);
      if (filterNode == null) {
         return responseContent;
      }

      try {
         JsonNode responseNode = JSON_MAPPER.readTree(responseContent);
         if (responseNode == null) {
            return responseContent;
         }

         // First apply jsonRetain if present.
         JsonNode retainNode = filterNode.get("jsonRetain");
         if (retainNode != null && retainNode.isArray()) {
            responseNode = applyRetain(responseNode, retainNode);
         }

         // Then apply jsonPatches if present.
         JsonNode patchesNode = filterNode.get("jsonPatches");
         if (patchesNode != null && patchesNode.isArray()) {
            responseNode = JsonPatch.apply(patchesNode, responseNode);
         }

         // Then apply compact if present.
         JsonNode compactNode = filterNode.get("compact");
         if (isCompactEnabled(compactNode)) {
            responseNode = applyCompaction(responseNode);
         }

         String result = JSON_MAPPER.writeValueAsString(responseNode);

         // Finally, convert to Toon format if requested.
         JsonNode convertToToonNode = filterNode.get("convertToToon");
         if (convertToToonNode != null && convertToToonNode.asBoolean()) {
            result = JToon.encodeJson(result);
         }

         return result;
      } catch (Exception e) {
         logger.warnf("Cannot apply output filter for tool '%s', returning original response: %s", toolName, e.getMessage());
         return responseContent;
      }
   }

   /**
    * Check if there are any output filters available for the service.
    * @return true if a ToolsOutputFilters artifact is attached to this service
    */
   public boolean hasFilters() {
      return getFiltersNode() != null;
   }

   /** Get the filter definition node for a specific tool. */
   private @Nullable JsonNode getFilterForTool(String toolName) {
      JsonNode filtersNode = getFiltersNode();
      if (filtersNode == null || !filtersNode.isObject()) {
         return null;
      }
      JsonNode toolFilter = filtersNode.get(toolName);
      return toolFilter != null && toolFilter.isObject() ? toolFilter : null;
   }

   /**
    * Retrieve the aggregated {@code filters} object node across <b>all</b> attached ToolsOutputFilters
    * artifacts. Each artifact is parsed and cached individually (keyed by its id); the per-tool filter
    * definitions are merged by tool name (first declaring artifact wins on collision, deterministic by
    * attachment order). Returns null when no attached artifact declares any filter.
    */
   private @Nullable JsonNode getFiltersNode() {
      if (attachedArtifacts == null || attachedArtifacts.isEmpty()) {
         return null;
      }
      ObjectNode merged = YAML_MAPPER.createObjectNode();
      for (ArtifactEntry artifact : attachedArtifacts) {
         if (!ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS.equals(artifact.type())) {
            continue;
         }
         JsonNode filtersNode = getFiltersNodeForArtifact(artifact);
         if (filtersNode != null && filtersNode.isObject()) {
            Iterator<String> tools = filtersNode.fieldNames();
            while (tools.hasNext()) {
               String tool = tools.next();
               if (!merged.has(tool)) {
                  merged.set(tool, filtersNode.get(tool));
               }
            }
         }
      }
      return merged.isEmpty() ? null : merged;
   }

   /** Parse and cache (keyed by artifact id) the {@code filters} sub-node of a single ToolsOutputFilters artifact. */
   private @Nullable JsonNode getFiltersNodeForArtifact(ArtifactEntry artifact) {
      if (workCache.get(artifact.id(), CACHE_KEYS_PREFIX) instanceof JsonNode cached) {
         logger.tracef("Got a cached value of ToolsOutputFilters JsonNode for artifact '%s'", artifact.id());
         return cached;
      }
      try {
         JsonNode artifactNode = YAML_MAPPER.readTree(artifact.content());
         JsonNode filtersNode = artifactNode.get("filters");
         if (filtersNode != null) {
            workCache.set(artifact.id(), CACHE_KEYS_PREFIX, filtersNode);
         }
         return filtersNode;
      } catch (Exception e) {
         logger.errorf(e, "Cannot read Reshapr ToolsOutputFilters artifact '%s' for service '%s'", artifact.id(), service.id());
         return null;
      }
   }

   /**
    * Apply the retain operation: keep only the branches specified by JSON Pointer paths.
    * If the response is an array, the retain is applied to each element.
    */
   private JsonNode applyRetain(JsonNode responseNode, JsonNode retainNode) {
      // Collect the set of pointer paths to retain.
      Set<String> retainPaths = new HashSet<>();
      for (JsonNode pathNode : retainNode) {
         retainPaths.add(pathNode.asText());
      }

      if (responseNode.isObject()) {
         return retainFields((ObjectNode) responseNode, retainPaths);
      } else if (responseNode.isArray()) {
         ArrayNode result = JSON_MAPPER.createArrayNode();
         for (JsonNode element : responseNode) {
            if (element.isObject()) {
               result.add(retainFields((ObjectNode) element, retainPaths));
            } else {
               result.add(element);
            }
         }
         return result;
      }
      return responseNode;
   }

   /**
    * Retain only the fields matching the given JSON Pointer paths in an object node.
    * A path like "/userInfo" retains the entire "userInfo" subtree.
    * A path like "/userInfo/name" retains only "name" within "userInfo".
    * A path like "/userInfo/0/name" retains only "name" within the first element of the "userInfo" array.
    */
   private ObjectNode retainFields(ObjectNode objectNode, Set<String> retainPaths) {
      ObjectNode result = JSON_MAPPER.createObjectNode();

      for (String path : retainPaths) {
         // Remove leading slash and split by slash.
         String[] segments = path.substring(1).split("/");
         copyPath(objectNode, result, segments, 0);
      }

      return result;
   }

   /** Recursively copy a path from source to result. */
   private void copyPath(JsonNode source, ObjectNode result, String[] segments, int index) {
      if (source == null || index >= segments.length) {
         return;
      }
      String segment = segments[index];
      JsonNode child = source.get(segment);
      if (child == null) {
         return;
      }

      if (index == segments.length - 1) {
         // Last segment: copy the whole subtree.
         result.set(segment, child.deepCopy());
      } else {
         // Intermediate segment: recurse into the child.
         if (child.isObject()) {
            ObjectNode nestedResult = result.has(segment) && result.get(segment).isObject()
                  ? (ObjectNode) result.get(segment) : JSON_MAPPER.createObjectNode();
            copyPath(child, nestedResult, segments, index + 1);
            result.set(segment, nestedResult);
         } else if (child.isArray()) {
            // We need to check if the next segment is an array index.
            int arrayIndex = -1;
            boolean isNextSegmentIndex = false;

            // Do we have a next segment?
            if (index + 1 < segments.length) {
               try {
                  arrayIndex = Integer.parseInt(segments[index + 1]);
                  if (arrayIndex >= 0 && arrayIndex < child.size()) {
                     isNextSegmentIndex = true;
                  }
               } catch (NumberFormatException ignored) {
                  // Not an array index, keep going.
               }
            }
            if (isNextSegmentIndex) {
               // The next segment is an array index, so we need only to copy the element at that index.
               JsonNode element = child.get(arrayIndex);
               ArrayNode arrayResult = JSON_MAPPER.createArrayNode();

               if (element.isObject()) {
                  ObjectNode elementResult = JSON_MAPPER.createObjectNode();
                  copyPath(element, elementResult, segments, index + 2);
                  arrayResult.add(elementResult);
               } else {
                  arrayResult.add(element);
               }

               result.set(segment, arrayResult);

            } else {
               // The next segment is not an array index, so we need to copy the whole array.
               ArrayNode arrayResult = JSON_MAPPER.createArrayNode();
               for (JsonNode element : child) {
                  if (element.isObject()) {
                     ObjectNode elementResult = JSON_MAPPER.createObjectNode();
                     copyPath(element, elementResult, segments, index + 1);
                     arrayResult.add(elementResult);
                  } else {
                     arrayResult.add(element);
                  }
               }
               result.set(segment, arrayResult);
            }
         }
      }
   }

   /**
    * Checks whether the compact filter option is enabled for a tool.
    */
   private boolean isCompactEnabled(@Nullable JsonNode compactNode) {
      if (compactNode == null) {
         return false;
      }
      if (compactNode.isBoolean()) {
         return compactNode.asBoolean();
      }
      return compactNode.isObject();
   }

   /**
    * Compacts a JSON node by recursively removing sparse values (null, empty strings, empty arrays, empty objects).
    * @param responseNode the root JSON node to compact
    * @return the compacted JSON node
    */
   private JsonNode applyCompaction(JsonNode responseNode) {
      return pruneNode(responseNode);
   }

   /**
    * Recursively prunes sparse values (null, empty strings, empty arrays, empty objects) from a JSON node.
    * @param node the current JSON node
    * @return the pruned JSON node
    */
   private JsonNode pruneNode(JsonNode node) {
      if (node.isObject()) {
         ObjectNode objectNode = (ObjectNode) node;
         Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
         while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode child = entry.getValue();

            if (child.isContainerNode()) {
               pruneNode(child);
            }

            if (isSparseValue(child)) {
               fields.remove();
            }
         }
      } else if (node.isArray()) {
         ArrayNode arrayNode = (ArrayNode) node;
         Iterator<JsonNode> elements = arrayNode.elements();
         while (elements.hasNext()) {
            JsonNode element = elements.next();
            if (element.isContainerNode()) {
               pruneNode(element);
            }

            if (isSparseValue(element)) {
               elements.remove();
            }
         }
      }
      return node;
   }

   /**
    * Checks if a JSON node represents a sparse value (null, empty string, empty array, or empty object).
    */
   private boolean isSparseValue(JsonNode node) {
      if (node.isNull()) {
         return true;
      }
      if (node.isTextual() && node.asText().isEmpty()) {
         return true;
      }
      if (node.isArray() && node.isEmpty()) {
         return true;
      }
      if (node.isObject() && node.isEmpty()) {
         return true;
      }
      return false;
   }
}
