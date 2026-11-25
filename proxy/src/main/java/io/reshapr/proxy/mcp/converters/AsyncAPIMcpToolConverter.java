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

import io.reshapr.proxy.events.ChannelInfo;
import io.reshapr.proxy.mcp.events.AsyncAPIChannels;
import io.reshapr.proxy.mcp.events.AsyncAPIDocument;
import io.reshapr.proxy.events.EventHub;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Converts the AsyncAPI {@code receive} operations (the application receives messages) into MCP tools
 * that publish a message to the channel. {@code send} operations are not tools: they are exposed as
 * resources with subscriptions by the events resource builder.
 * @author laurent
 */
public class AsyncAPIMcpToolConverter extends McpToolConverter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "aapimcptc-";

   private final ExpositionEntry exposition;
   private final WorkCache workCache;
   private final ObjectMapper mapper;
   private final EventHub eventHub;

   public AsyncAPIMcpToolConverter(ExpositionEntry exposition, WorkCache workCache, ObjectMapper mapper,
                                   EventHub eventHub) {
      this.exposition = exposition;
      this.workCache = workCache;
      this.mapper = mapper;
      this.eventHub = eventHub;
   }

   @Override
   public List<OperationEntry> getAvailableOperations(ServiceEntry service) {
      if (!eventHub.capabilities(exposition).publish()) {
         return List.of();
      }

      return service.operations().stream()
            .filter(operation -> AsyncAPIChannels.RECEIVE.equalsIgnoreCase(operation.method()))
            .toList();
   }

   @Override
   public String getToolDescription(OperationEntry operation) {
      ChannelInfo channel = getChannel(operation);
      String base = "Publish an event on channel '" + operation.action() + "'";
      return channel != null && channel.description() != null ? base + ": " + channel.description() : base;
   }

   @Override
   public McpSchema.JsonSchema getInputSchema(OperationEntry operation) {
      ObjectNode inputSchemaNode = mapper.createObjectNode();
      try {
         inputSchemaNode = getInputSchemaNode(operation);
      } catch (Exception e) {
         logger.error("Exception while trying to get input schema", e);
      }
      return mapper.convertValue(inputSchemaNode, McpSchema.JsonSchema.class);
   }

   @Override
   public Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration,
                                   McpSchema.SimpleRequest request, Map<String, List<String>> headers) {
      try {
         ChannelInfo channel = getChannel(operation);
         if (channel == null) {
            logger.errorf("Unknown channel for operation '%s'", operation.name());
            return new Response("Unknown channel for operation '" + operation.name() + "'", true);
         }
         Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
         
         // Non-object payloads (string, array...) are wrapped under a single "payload" argument: unwrap them.
         String payloadType = channel.payloadSchema() == null ? null : channel.payloadSchema().path("type").asText(null);
         boolean isWrappedPayload = arguments.size() == 1 && arguments.containsKey("payload")
               && !"object".equals(payloadType);

         JsonNode payloadNode = isWrappedPayload ? mapper.valueToTree(arguments.get("payload")) : mapper.valueToTree(arguments);
         String payload = payloadNode.isTextual() ? payloadNode.asText() : mapper.writeValueAsString(payloadNode);

         EventHub.Receipt receipt = eventHub.publish(exposition, channel, null, payload);
         ObjectNode response = mapper.createObjectNode();
         response.put("status", "published");
         response.put("destination", receipt.destination());
         response.put("messageId", receipt.messageId());
         if (receipt.traceId() != null) {
            response.put("traceId", receipt.traceId());
         }
         return new Response(mapper.writeValueAsString(response), false);
      } catch (Exception e) {
         logger.errorf(e, "Exception while publishing event for operation '%s'", operation.name());
         ObjectNode failure = mapper.createObjectNode();
         failure.put("status", "failure");
         failure.put("destination", operation.action());
         failure.put("error", "Failed to publish event: " + e.getMessage());
         return new Response(failure.toString(), true);
      }
   }

   @Override
   public Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request,
                                           Map<String, List<String>> headers) {
      return null;
   }

   /** Get the channel information for a given operation. */
   private ChannelInfo getChannel(OperationEntry operation) {
      AsyncAPIDocument document = AsyncAPIChannels.getDocument(exposition, workCache);
      return document == null ? null : AsyncAPIChannels.getChannelForOperation(exposition, operation, document, workCache);
   }

   /** */
   private ObjectNode getInputSchemaNode(OperationEntry operation) {
      String major = exposition.mainArtifact().id();
      String minor = CACHE_KEYS_PREFIX + operation.hashCode() + "-schema";
      Object value = workCache.get(major, minor);
      if (value instanceof ObjectNode inputSchemaNode) {
         logger.tracef("Got a cached value of InputSchemaNode for service '%s' and operation '%s'", exposition.service().id(), operation.name());
         return inputSchemaNode;
      }

      // Compute new value to cache.
      logger.debugf("Need to build the InputSchemaNode for service '%s' and operation '%s'", exposition.service().id(), operation.name());
      ObjectNode inputSchemaNode = mapper.createObjectNode();
      ChannelInfo channel = getChannel(operation);
      ObjectNode payload = channel == null ? null : channel.payloadSchema();
      if (payload != null && "object".equals(payload.path("type").asText(null))) {
         inputSchemaNode.put("type", "object");
         inputSchemaNode.set("properties", payload.has("properties") ? payload.get("properties") : mapper.createObjectNode());
         if (payload.has("required")) {
            inputSchemaNode.set("required", payload.get("required"));
         }
      } else {
         // Non-object payloads are wrapped in a single "payload" argument.
         inputSchemaNode.put("type", "object");
         ObjectNode properties = inputSchemaNode.putObject("properties");
         properties.set("payload", payload != null ? payload : mapper.createObjectNode().put("type", "string"));
         inputSchemaNode.putArray("required").add("payload");
      }
      return inputSchemaNode;
   }
}
