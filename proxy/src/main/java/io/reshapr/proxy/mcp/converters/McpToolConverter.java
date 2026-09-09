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
import io.reshapr.proxy.mcp.DeclaredTool;
import io.reshapr.proxy.mcp.ResponseAttributes;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ContentUtil;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ResourceEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.registry.ToolEntry;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility base class for converting a Reshapr Service and Operation into an MCP Tool.
 * @author laurent
 */
public abstract class McpToolConverter {


   /**
    * Get the list of available operations on this converter for the given service.
    * @param service The service to extract operations from.
    * @return The list of available operations.
    */
   public List<OperationEntry> getAvailableOperations(ServiceEntry service) {
      return service.operations();
   }

   /**
    * Get the list of operations effectively exposed for the given service under the provided
    * configuration plan. The default implementation restricts the available operations with the
    * plan's included/excluded lists. Converters that reshape the API surface (e.g. custom tools)
    * override this to compose the plan restriction with their own reshaping.
    * @param service       The service to extract operations from.
    * @param configuration The configuration plan holding the include/exclude lists.
    * @return The list of exposed operations.
    */
   public List<OperationEntry> getExposedOperations(ServiceEntry service, ConfigurationEntry configuration) {
      return getAvailableOperations(service).stream()
            .filter(operation -> configuration.exposesOperation(operation.name()))
            .toList();
   }

   /**
    * Get the list of operations that can be resolved for an internal (custom-tool script) tool call on the
    * given service, independently of the configuration plan exposure. The default implementation returns all
    * available operations; converters that reshape the API surface (e.g. custom tools) override this to also
    * surface the raw operations they hide from the exposed surface, so a script may call them directly.
    * @param service The service to extract operations from.
    * @return The list of resolvable operations.
    */
   public List<OperationEntry> getResolvableOperations(ServiceEntry service) {
      return getAvailableOperations(service);
   }

   /**
    * Extract the name of the tool from the operation.
    * @param operation The operation to extract the name from.
    * @return The tool name
    */
   public String getToolName(OperationEntry operation) {
      return operation.name();
   }

   /**
    * Extract the description of the tool from the operation.
    * @param operation The operation to extract the description from.
    * @return The tool description
    */
   public abstract String getToolDescription(OperationEntry operation);

   /**
    * Extract the metadata of the tool from the operation.
    * @param registry  The gateway registry to use for resource lookup.
    * @param service   The service to which the operation belongs.
    * @param operation The operation to extract the metadata from.
    * @return The tool metadata map
    */
   public @Nullable Map<String, Object> getToolMetadata(GatewayRegistry registry, ServiceEntry service, OperationEntry operation) {
      // Only manage UI resource for now.
      ToolEntry tool = new ToolEntry(service.id(), service.organizationId(), getToolName(operation));
      ResourceEntry resource = registry.getResourceForTool(tool);
      if (resource != null) {
         return Map.of(
               "ui", Map.of("resourceUri", resource.resourceUri())
         );
      }
      return null;
   }

   /**
    * Extract the input schema of the tool from the operation.
    * @param operation The operation to extract the input schema from.
    * @return The tool input schema following the 2024-11-05 MCP spec
    */
   public abstract McpSchema.JsonSchema getInputSchema(OperationEntry operation);

   /**
    * Invoke the tool with the given request and return the response in Microcks domain object.
    * @param operation The operation to invoke the tool on.
    * @param configuration The configuration to apply when invoking the tool
    * @param request   The request to send to the tool.
    * @param headers   Simple representation of headers transmitted at the protocol level.
    * @return The response from the tool.
    */
   public abstract Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration,
                                            McpSchema.SimpleRequest request, Map<String, List<String>> headers);

   /**
    * Invoke the tool with the given request and return the response in Microcks domain object.
    * @param operation The operation to invoke the tool on.
    * @param request   The request to send to the tool.
    * @param headers   Simple representation of headers transmitted at the protocol level.
    * @return The response from the tool.
    */
   public abstract Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request,
                                                 Map<String, List<String>> headers);

   /**
    * If the given operation declares a set of tools it may call, return that list (its allow-list);
    * otherwise return {@code null}. Used as a security allow-list and to drive the elicitation
    * pre-flight before invoking the operation (e.g. for script-based custom tools).
    * @param operation The operation to inspect.
    * @return The declared tools allow-list, or {@code null} if the operation declares none.
    */
   public @Nullable List<DeclaredTool> getDeclaredTools(OperationEntry operation) {
      return null;
   }

   /**
    * Response record holding content, fault indicator and response-level attributes.
    * The {@code attrs} carrier surfaces backend-provided response metadata (e.g. the
    * {@code X-Reshapr-Upstream-Service-Time} header) up to the MCP response layer.
    * @param content the response content
    * @param isFault indicates whether response is a fault
    * @param attrs   response-level attributes propagated up to the MCP HTTP response
    */
   public record Response(
         String content,
         boolean isFault,
         ResponseAttributes attrs
   ) {
      /** Backward-compatible constructor building a response with no attributes. */
      public Response(String content, boolean isFault) {
         this(content, isFault, ResponseAttributes.empty());
      }
   }

   /** Prepare the Http headers by sanitizing them. */
   protected Map<String, List<String>> sanitizeHttpHeaders(Map<String, List<String>> headers) {
      return headers.entrySet().stream().filter(entry -> !"content-length".equalsIgnoreCase(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
   }

   /** Depending on result encoding, extract the response content as a string. */
   protected String extractResponseContent(BackendResponse result) throws IOException {
      return ContentUtil.extractResponseContent(result);
   }
}
