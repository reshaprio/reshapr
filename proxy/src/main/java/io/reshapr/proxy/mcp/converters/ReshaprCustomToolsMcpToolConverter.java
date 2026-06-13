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
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import io.github.microcks.util.ObjectMapperFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
   private static final String ARGUMENT_START_MARKER = "${";
   private static final String ARGUMENT_END_MARKER = "}";

   private final ServiceEntry service;
   private final List<ArtifactEntry> attachedArtifacts;
   private final WorkCache workCache;
   private final McpToolConverter protocolToolConverter;

   /**
    * Creates a ReshaprCustomToolsMcpToolConverter with required dependencies.
    */
   public ReshaprCustomToolsMcpToolConverter(ServiceEntry service, @Nullable List<ArtifactEntry> attachedArtifacts,
                                             WorkCache workCache, McpToolConverter protocolToolConverter) {
      this.service = service;
      this.attachedArtifacts = attachedArtifacts;
      this.workCache = workCache;
      this.protocolToolConverter = protocolToolConverter;
   }

   @Override
   public List<OperationEntry> getAvailableOperations(ServiceEntry service) {
      List<OperationEntry> operationEntries = new ArrayList<>();

      // First, check cache to see if we deal with a custom tool.
      JsonNode customToolsNode = getCustomToolsNode();
      if (customToolsNode != null) {
         // We must accumulate target tools to avoid duplicates.
         Set<String> targetTools = new HashSet<>();

         Iterator<Map.Entry<String, JsonNode>> customToolNode = customToolsNode.fields();
         while (customToolNode.hasNext()) {
            Map.Entry<String, JsonNode> entry = customToolNode.next();
            // Add the custom tool as an operation entry and accumulate target tool.
            operationEntries.add(new OperationEntry(entry.getKey(), null, null, null, null));
            targetTools.add(entry.getValue().path("tool").asText());
         }

         // Then get the protocol specific tools excluding those that are already targeted by custom tools.
         protocolToolConverter.getAvailableOperations(service).stream()
               .filter(operationEntry -> !targetTools.contains(protocolToolConverter.getToolName(operationEntry)))
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

         // Find the target operation on service.
         String target = customToolNode.path("tool").asText();
         targetOperation = service.operations().stream()
               .filter(entry -> entry.name().equals(target))
               .findFirst().orElse(null);

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
   public Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request, Map<String, List<String>> headers) {
      return null;
   }

   /** Retrieve the `customTools` node of a CustomTools kind yaml attachment. */
   private @Nullable JsonNode getCustomToolsNode() {
      String major = String.valueOf(service.hashCode());
      if (workCache.get(major, CACHE_KEYS_PREFIX) instanceof JsonNode customToolsNode) {
         logger.tracef("Got a cached value of CustomTools JsonNode for service '%s'", service.id());
         return customToolsNode;
      }

      // Avoid errors if no attached artifacts.
      if (attachedArtifacts == null || attachedArtifacts.isEmpty()) {
         return null;
      }

      // Check the existence of a Reshapr CustomTools artifact.
      Optional<ArtifactEntry> customToolsArtifact = attachedArtifacts.stream()
            .filter(artifactEntry -> ArtifactEntryType.RESHAPR_CUSTOM_TOOLS.equals(artifactEntry.type()))
            .findFirst();
      if (customToolsArtifact.isEmpty()) {
         return null;
      }

      // Compute new value to cache.
      logger.debugf("Need to build CustomTools for service '%s'", service.id());
      try {
         JsonNode artifactNode = YAML_MAPPER.readTree(customToolsArtifact.get().content());
         JsonNode promptsNode = artifactNode.get("customTools");

         workCache.set(major, CACHE_KEYS_PREFIX, promptsNode);
         return promptsNode;
      } catch (Exception e) {
         logger.errorf(e, "Cannot read Reshapr Prompts artifact for service '%s'", service.id());
         return null;
      }
   }

   /** Returns true if the provided name matches a tool name within the `customTools` node. */
   private boolean isCustomTool(JsonNode customToolsNode, String name) {
      return customToolsNode != null && customToolsNode.has(name);
   }

   /** Within a CustomTools attachment, retrieve the `arguments` node within a tool. */
   protected Map<String, Object> getCustomToolTargetArguments(OperationEntry operation, JsonNode customToolNode) {
      String major = String.valueOf(service.hashCode());
      String minor = CACHE_KEYS_PREFIX + operation.name();
      Object value = workCache.get(major, minor);
      if (value instanceof Map<?, ?> toolTargetArguments) {
         logger.tracef("Got a cached value of CustomTool target arguments for service '%s' and operation '%s'", service.id(), operation.name());
         return (Map<String, Object>) toolTargetArguments;
      }

      // Compute new value to cache.
      logger.debugf("Need to build the CustomTool target arguments for service '%s' and operation '%s'", service.id(), operation.name());

      Map<String, Object> arguments = YAML_MAPPER.convertValue(customToolNode.get("arguments"),
            new TypeReference<HashMap<String, Object>>() {});
      workCache.set(major, minor, arguments);
      return arguments;
   }

   /** Build a complete map from incoming custom tools request where keys are newted param names (separated by `.`)  with their values. */
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
