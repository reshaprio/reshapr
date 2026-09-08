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
import io.reshapr.proxy.mcp.DeclaredTool;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.script.CustomToolScriptRunner;
import io.reshapr.proxy.mcp.script.ReshaprToolsBuiltins;
import io.reshapr.proxy.mcp.script.ScriptExecutionContext;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An implementation of McpToolConverter that uses the Custom Tools attached artifacts
 * to allow the re-shaping of an API default operations into custom tools. Such a converter wraps
 * a protocol specific converter as a delegate.
 * @author laurent
 */
public class ReshaprCustomToolsMcpToolConverter extends McpToolConverter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "mctmcptc-";

   private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.getYamlObjectMapper();
   private static final ObjectMapper JSON_MAPPER = ObjectMapperFactory.getJsonObjectMapper();

   private static final String ARGUMENT_START_MARKER = "${";
   private static final String ARGUMENT_END_MARKER = "}";
   private static final String TOOL_NODE = "tool";
   private static final String SCRIPT_NODE = "script";

   private final ExpositionEntry exposition;
   private final List<ArtifactEntry> attachedArtifacts;
   private final WorkCache workCache;
   private final McpToolConverter protocolToolConverter;
   private final ToolCallExecutor toolCallExecutor;
   private final GatewayRegistry gatewayRegistry;

   /**
    * Creates a ReshaprCustomToolsMcpToolConverter with the dependencies required for script support.
    * @param exposition The exposition these custom tools relate to.
    * @param workCache The work cache for temporary data storage.
    * @param protocolToolConverter The wrapped protocol-specific converter.
    * @param toolCallExecutor The executor used by scripts to call other tools (may be null for template-only usage).
    * @param gatewayRegistry The registry used to resolve cross-service script calls (may be null for template-only usage).
    */
   public ReshaprCustomToolsMcpToolConverter(ExpositionEntry exposition, WorkCache workCache,
                                             McpToolConverter protocolToolConverter,
                                             @Nullable ToolCallExecutor toolCallExecutor,
                                             @Nullable GatewayRegistry gatewayRegistry) {
      this.exposition = exposition;
      this.attachedArtifacts = exposition.attachedArtifacts();
      this.workCache = workCache;
      this.protocolToolConverter = protocolToolConverter;
      this.toolCallExecutor = toolCallExecutor;
      this.gatewayRegistry = gatewayRegistry;
   }

   /**
    * Creates a ReshaprCustomToolsMcpToolConverter for template-only custom tools (no script support).
    * @param exposition The exposition these custom tools relate to.
    * @param workCache The work cache for temporary data storage.
    * @param protocolToolConverter The wrapped protocol-specific converter.
    */
   public ReshaprCustomToolsMcpToolConverter(ExpositionEntry exposition,
                                             WorkCache workCache, McpToolConverter protocolToolConverter) {
      this(exposition, workCache, protocolToolConverter, null, null);
   }

   @Override
   public List<OperationEntry> getAvailableOperations(ServiceEntry service) {
      List<OperationEntry> operationEntries = new ArrayList<>();

      // First, check cache to see if we deal with a custom tool.
      JsonNode customToolsNode = getCustomToolsNode();
      if (customToolsNode != null) {
         // We must accumulate target tools to avoid duplicates.
         Set<String> targetTools = new HashSet<>();

         Set<Map.Entry<String, JsonNode>> customToolNodes = customToolsNode.properties();
         for (Map.Entry<String, JsonNode> entry : customToolNodes) {
            // Add the custom tool as an operation entry.
            operationEntries.add(new OperationEntry(entry.getKey(), null, null, null, null));
            // For declarative (template) custom tools, accumulate the single target tool so it gets
            // hidden from the protocol tools. Script custom tools don't hide their declared tools.
            JsonNode targetToolNode = entry.getValue().path(TOOL_NODE);
            if (!targetToolNode.isMissingNode() && !targetToolNode.asText().isEmpty()) {
               targetTools.add(targetToolNode.asText());
            }
         }

         // Then get the protocol specific tools excluding those that are already targeted by custom tools.
         protocolToolConverter.getAvailableOperations(service).stream()
               .filter(operationEntry -> !targetTools.contains(operationEntry.name())
                     && !targetTools.contains(protocolToolConverter.getToolName(operationEntry)))
               .forEach(operationEntries::add);
      } else {
         // Else delegate to protocol specific tool.
         operationEntries = protocolToolConverter.getAvailableOperations(service);
      }
      return operationEntries;
   }

   @Override
   public String getToolName(OperationEntry operation) {
      // First, check cache to see if we deal with a custom tool.
      JsonNode customToolsNode = getCustomToolsNode();
      if (isCustomTool(customToolsNode, operation.name())) {
         return operation.name();
      }
      // Else delegate to protocol specific tool.
      return protocolToolConverter.getToolName(operation);
   }

   @Override
   public String getToolDescription(OperationEntry operation) {
      // First, check cache to see if we have already loaded prompts.
      JsonNode customToolsNode = getCustomToolsNode();
      if (isCustomTool(customToolsNode, operation.name())) {
         JsonNode customToolNode = customToolsNode.get(operation.name());
         return customToolNode.path("description").asText();
      }
      // Else delegate to protocol specific tool.
      return protocolToolConverter.getToolName(operation);
   }

   @Override
   public McpSchema.JsonSchema getInputSchema(OperationEntry operation) {
      // First, check cache to see if we have already loaded prompts.
      JsonNode customToolsNode = getCustomToolsNode();
      if (isCustomTool(customToolsNode, operation.name())) {
         JsonNode customToolNode = customToolsNode.get(operation.name());

         JsonNode inputSchemaNode = customToolNode.get("input");
         return YAML_MAPPER.convertValue(inputSchemaNode, McpSchema.JsonSchema.class);
      }
      // Else delegate to protocol specific tool.
      return protocolToolConverter.getInputSchema(operation);
   }

   @Override
   public Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration, McpSchema.SimpleRequest request, Map<String, List<String>> headers) {

      OperationEntry targetOperation = operation;
      McpSchema.SimpleRequest targetRequest = request;

      // First, check cache to see if we have already loaded prompts.
      JsonNode customToolsNode = getCustomToolsNode();
      if (isCustomTool(customToolsNode, operation.name())) {
         JsonNode customToolNode = customToolsNode.get(operation.name());

         // If the custom tool is backed by a script, execute it.
         if (customToolNode.has(SCRIPT_NODE)) {
            return executeScript(operation, customToolNode, request, headers);
         }

         // Otherwise, this is a declarative (template) custom tool: find the target operation on service.
         String target = customToolNode.path(TOOL_NODE).asText();
         targetOperation = exposition.service().operations().stream()
               .filter(entry -> entry.name().equals(target))
               .findFirst().orElse(null);

         // The custom tool may reference a tool that does not exist on the current service (e.g. a typo or a
         // renamed/removed operation). Fail fast with a clear, MCP-mappable error instead of letting a null
         // target operation reach the delegate converter and surface as an opaque NullPointerException.
         if (targetOperation == null) {
            throw new CustomToolResolutionException(operation.name(), target);
         }

         // First, create a map of arguments from incoming request.
         Map<String, Object> customArgumentsValues = new HashMap<>();
         completeCustomArgumentsMap(customArgumentsValues, request.arguments(), "");

         // Then, from the templated target arguments, we need to build the target arguments
         // replacing ${} placeholder with their actual values from incoming map.
         Map<String, Object> targetArgumentsTemplate = getCustomToolTargetArguments(operation, customToolNode);
         Map<String, Object> targetArguments = buildTargetArguments(targetArgumentsTemplate, customArgumentsValues);

         // Rebuild a request matching target.
         targetRequest = new McpSchema.SimpleRequest(target, targetArguments);
      }

      return protocolToolConverter.getCallResponse(targetOperation, configuration, targetRequest, headers);
   }

   @Override
   public @Nullable List<DeclaredTool> getDeclaredTools(OperationEntry operation) {
      JsonNode customToolsNode = getCustomToolsNode();
      if (isCustomTool(customToolsNode, operation.name())) {
         JsonNode customToolNode = customToolsNode.get(operation.name());
         if (customToolNode.has(SCRIPT_NODE)) {
            return parseDeclaredTools(customToolNode);
         }
      }
      return null;
   }

   /** Execute a script-based custom tool and return its JSON result as a Response. */
   private Response executeScript(OperationEntry operation, JsonNode customToolNode, McpSchema.SimpleRequest request,
                                  Map<String, List<String>> headers) {
      if (toolCallExecutor == null || gatewayRegistry == null) {
         logger.errorf("Cannot execute script custom tool '%s': executor/registry not available", operation.name());
         return new Response("Script execution context is not available", true);
      }

      // Guard-rail: bound cross-script recursion depth.
      int parentDepth = ScriptExecutionContext.currentDepth();
      if (parentDepth >= toolCallExecutor.scriptMaxDepth()) {
         logger.warnf("Maximum script nesting depth (%d) reached for tool '%s'",
               toolCallExecutor.scriptMaxDepth(), operation.name());
         return new Response("Maximum script nesting depth reached", true);
      }

      String script = customToolNode.path(SCRIPT_NODE).asText();
      List<DeclaredTool> declaredTools = parseDeclaredTools(customToolNode);

      ReshaprToolsBuiltins builtins = new ReshaprToolsBuiltins(exposition, gatewayRegistry, toolCallExecutor,
            headers, declaredTools, toolCallExecutor.scriptMaxToolCalls());
      CustomToolScriptRunner runner = new CustomToolScriptRunner(JSON_MAPPER, toolCallExecutor.scriptTimeoutMillis());

      try {
         String result = runner.run(script, request.arguments(), builtins, parentDepth + 1);
         // Aggregate response attributes across all backends reached by the script (e.g. MAX of
         // X-Reshapr-Upstream-Service-Time when the script fans out to several tools).
         return new Response(result, false, builtins.accumulatedAttrs());
      } catch (CustomToolScriptRunner.CustomToolScriptException e) {
         // A thrown error (or rs.fail) becomes an MCP tool error, surfacing the script-provided content.
         logger.warnf("Script custom tool '%s' failed: %s", operation.name(), e.getMessage());
         // For a compilation error, prepend the failing tool name so the caller knows which custom
         // tool's script did not compile (the concise syntax detail comes from the runner).
         String errorContent = e.isCompilationError()
               ? "Custom tool '" + operation.name() + "' failed to compile: " + e.errorContent()
               : e.errorContent();
         return new Response(errorContent, true, builtins.accumulatedAttrs());
      } catch (Exception e) {
         logger.errorf(e, "Exception while running script custom tool '%s'", operation.name());
         return new Response("Script execution failed", true, builtins.accumulatedAttrs());
      }
   }

   /** Parse the `tools` allow-list declared by a script custom tool. */
   private List<DeclaredTool> parseDeclaredTools(JsonNode customToolNode) {
      List<DeclaredTool> declaredTools = new ArrayList<>();
      JsonNode toolsNode = customToolNode.path("tools");
      if (toolsNode.isArray()) {
         for (JsonNode toolNode : toolsNode) {
            String tool = toolNode.path(TOOL_NODE).asText(null);
            if (tool == null) {
               continue;
            }
            JsonNode serviceNode = toolNode.path("service");
            String serviceCoordinate = serviceNode.isMissingNode() ? null : serviceNode.asText(null);
            declaredTools.add(new DeclaredTool(serviceCoordinate, tool));
         }
      }
      return declaredTools;
   }

   @Override
   public Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request, Map<String, List<String>> headers) {
      return null;
   }

   /**
    * Retrieve the aggregated {@code customTools} node across <b>all</b> attached CustomTools artifacts. Each
    * artifact is parsed and cached individually (keyed by its id); the tool definitions are merged by tool
    * name (first declaring artifact wins on collision, deterministic by attachment order). Returns null when
    * no attached artifact declares any custom tool.
    */
   private @Nullable JsonNode getCustomToolsNode() {
      if (attachedArtifacts == null || attachedArtifacts.isEmpty()) {
         return null;
      }
      ObjectNode merged = YAML_MAPPER.createObjectNode();
      for (ArtifactEntry artifact : attachedArtifacts) {
         if (!ArtifactEntryType.RESHAPR_CUSTOM_TOOLS.equals(artifact.type())) {
            continue;
         }
         JsonNode customToolsNode = getCustomToolsNodeForArtifact(artifact);
         if (customToolsNode != null && customToolsNode.isObject()) {
            Iterator<String> names = customToolsNode.fieldNames();
            while (names.hasNext()) {
               String name = names.next();
               if (!merged.has(name)) {
                  merged.set(name, customToolsNode.get(name));
               }
            }
         }
      }
      return merged.isEmpty() ? null : merged;
   }

   /** Parse and cache (keyed by artifact id) the {@code customTools} sub-node of a single CustomTools artifact. */
   private @Nullable JsonNode getCustomToolsNodeForArtifact(ArtifactEntry artifact) {
      if (workCache.get(artifact.id(), CACHE_KEYS_PREFIX) instanceof JsonNode cached) {
         logger.tracef("Got a cached value of CustomTools JsonNode for artifact '%s'", artifact.id());
         return cached;
      }
      try {
         JsonNode artifactNode = YAML_MAPPER.readTree(artifact.content());
         JsonNode customToolsNode = artifactNode.get("customTools");
         if (customToolsNode != null) {
            workCache.set(artifact.id(), CACHE_KEYS_PREFIX, customToolsNode);
         }
         return customToolsNode;
      } catch (Exception e) {
         logger.errorf(e, "Cannot read Reshapr CustomTools artifact '%s' for service '%s'", artifact.id(), exposition.service().id());
         return null;
      }
   }

   /** Returns true if the provided name matches a tool name within the `customTools` node. */
   private boolean isCustomTool(JsonNode customToolsNode, String name) {
      return customToolsNode != null && customToolsNode.has(name);
   }

   /** Within a CustomTools attachment, retrieve the `arguments` node within a tool. */
   protected Map<String, Object> getCustomToolTargetArguments(OperationEntry operation, JsonNode customToolNode) {
      // The custom tool node comes from an already parsed-and-cached artifact node, so we simply convert its
      // (small) `arguments` template on the fly. No per-operation cache is needed anymore.
      logger.debugf("Building the CustomTool target arguments for service '%s' and operation '%s'", exposition.service().id(), operation.name());
      return YAML_MAPPER.convertValue(customToolNode.get("arguments"),
            new TypeReference<HashMap<String, Object>>() {});
   }

   /** Build a complete map from incoming custom tools request where keys are nested param names (separated by `.`)  with their values. */
   protected static void completeCustomArgumentsMap(Map<String, Object> customArgumentsValues,
                                                 Map<String, Object> requestArguments,
                                                 String keyPrefix) {
      requestArguments.forEach((argumentName, argumentValue) -> {
         if (argumentValue instanceof Map<?, ?> objectArgumentValue) {
            // Recurse, adding the argument name as a subkey to the prefix.
            completeCustomArgumentsMap(customArgumentsValues, (Map<String, Object>) objectArgumentValue,
                  keyPrefix + argumentName + ".");
         } else {
            customArgumentsValues.put(keyPrefix + argumentName, argumentValue);
         }
      });
   }

   /** Build the target tool arguments by replacing templated values with the one coming from a custom tool request arguments. */
   protected static Map<String, Object> buildTargetArguments(Map<String, Object> targetArgumentsTemplate,
                                                          Map<String, Object> customArgumentsValues) {
      HashMap<String, Object> result = HashMap.newHashMap(targetArgumentsTemplate.size());

      targetArgumentsTemplate.forEach((argumentMapKey, argumentMapValue) -> {
         if (argumentMapValue instanceof Map<?, ?> argumentMapValueObj) {
            result.put(argumentMapKey, buildTargetArguments(
                  (Map<String, Object>) argumentMapValueObj,
                  customArgumentsValues));
         } else if (argumentMapValue instanceof String argumentMapValueStr) {
            if (argumentMapValueStr.startsWith(ARGUMENT_START_MARKER) && argumentMapValueStr.endsWith(ARGUMENT_END_MARKER)) {
               String variable = argumentMapValueStr.substring(ARGUMENT_START_MARKER.length(),
                     argumentMapValueStr.length() - ARGUMENT_END_MARKER.length());
               if (customArgumentsValues.containsKey(variable)) {
                  result.put(argumentMapKey, customArgumentsValues.get(variable));
               }
            } else {
               result.put(argumentMapKey, argumentMapValue);
            }
         } else {
            result.put(argumentMapKey, argumentMapValue);
         }
      });

      return result;
   }
}
