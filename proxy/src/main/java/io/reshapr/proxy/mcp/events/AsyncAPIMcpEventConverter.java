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
package io.reshapr.proxy.mcp.events;

import io.reshapr.proxy.events.Channel;
import io.reshapr.proxy.events.ChannelInfo;
import io.reshapr.proxy.events.BrokerCapabilities;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ExpositionEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the MCP Events representation ({@link McpEventsSchema}) of an ASYNC exposition: one event type per exposed
 * {@code send} channel, named after the channel key, with its input schema derived from the AsyncAPI message payload.
 * The delivery modes follow what the broker technology can do.
 * @author laurent
 */
public class AsyncAPIMcpEventConverter {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(AsyncAPIMcpEventConverter.class);

   private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");

   private final ExpositionEntry exposition;
   private final WorkCache workCache;
   private final ObjectMapper mapper;
   private final BrokerCapabilities capabilities;

   /**
    * Builds a converter for the given exposition, using the given work cache and mapper, and the given broker capabilities.
    * @param exposition the exposition entry to convert
    * @param workCache the work cache to use for resolving references
    * @param mapper the object mapper to use for parsing JSON
    * @param capabilities the broker capabilities to use for determining delivery modes
    * @throws IllegalArgumentException if the exposition is not of type ASYNC
    */
   public AsyncAPIMcpEventConverter(ExpositionEntry exposition, WorkCache workCache, ObjectMapper mapper,
                                    BrokerCapabilities capabilities) {
      this.exposition = exposition;
      this.workCache = workCache;
      this.mapper = mapper;
      this.capabilities = capabilities;
   }

   /** The event types exposed by the exposition; none when the broker offers neither poll nor push. */
   public List<McpEventsSchema.EventType> listEventTypes() {
      List<String> delivery = new ArrayList<>();
      if (capabilities.replay()) {
         delivery.add(McpEventsSchema.DELIVERY_POLL);
      }
      if (capabilities.push()) {
         delivery.add(McpEventsSchema.DELIVERY_PUSH);
      }
      if (delivery.isEmpty()) {
         return List.of();
      }
      List<McpEventsSchema.EventType> types = new ArrayList<>();
      for (Channel channel : AsyncAPIChannels.channels(exposition, workCache, false)) {
         ChannelInfo info = channel.info();
         String description = info.description() != null ? info.description()
               : "Events published on '" + info.address() + "'";
         types.add(new McpEventsSchema.EventType(info.key(), description, delivery,
               getInputSchemaNode(info.payloadSchema()), info.payloadSchema()));
      }
      return types;
   }

   @Nullable
   public McpEventsSchema.EventType findEventType(String name) {
      return listEventTypes().stream().filter(type -> type.name().equals(name)).findFirst().orElse(null);
   }

   /** An event occurrence as carried on the wire; the data is the payload as JSON when it is, else a string. */
   public McpEventsSchema.EventOccurrence toOccurrence(String name, String eventId, long timestamp, String payload,
                                                       @Nullable String cursor) {
      return new McpEventsSchema.EventOccurrence(eventId, name, Instant.ofEpochMilli(timestamp).toString(),
            parseData(payload), cursor);
   }

   /** The payload as JSON when it is, else as a plain string. */
   public Object parseData(String payload) {
      try {
         return mapper.readTree(payload);
      } catch (Exception _) {
         return payload;
      }
   }

   /**
    * Subscription arguments: every scalar top-level property of the payload can be used as an equality filter,
    * all optional. Nothing else is accepted.
    */
   public ObjectNode getInputSchemaNode(@Nullable ObjectNode payloadSchema) {
      ObjectNode inputSchemaNode = mapper.createObjectNode();
      inputSchemaNode.put("type", "object");
      ObjectNode properties = inputSchemaNode.putObject("properties");
      if (payloadSchema != null && payloadSchema.path("properties") instanceof ObjectNode payloadProperties) {
         Iterator<Map.Entry<String, JsonNode>> it = payloadProperties.fields();
         while (it.hasNext()) {
            Map.Entry<String, JsonNode> property = it.next();
            String type = property.getValue().path("type").asText("");
            if (SCALAR_TYPES.contains(type)) {
               ObjectNode argument = properties.putObject(property.getKey());
               argument.put("type", type);
               argument.put("description", "Only events whose '" + property.getKey() + "' equals this value");
            }
         }
      }
      inputSchemaNode.put("additionalProperties", false);
      return inputSchemaNode;
   }

   /**
    * Check subscription arguments against the input schema.
    * @throws IllegalArgumentException naming the first offending argument.
    */
   public static void validateArguments(McpEventsSchema.EventType type, @Nullable Map<?, ?> arguments) {
      if (arguments == null) {
         return;
      }
      JsonNode properties = type.inputSchema().path("properties");
      for (Map.Entry<?, ?> argument : arguments.entrySet()) {
         String key = String.valueOf(argument.getKey());
         JsonNode property = properties.get(key);
         if (property == null) {
            logger.warnf("Unknown argument '%s' for event '%s'", key, type.name());
            throw new IllegalArgumentException("Unknown argument '" + key + "' for event '" + type.name() + "'");
         }
         Object value = argument.getValue();
         boolean valid = switch (property.path("type").asText()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> false;
         };
         if (!valid) {
            throw new IllegalArgumentException("Argument '" + key + "' must be of type "
                  + property.path("type").asText());
         }
      }
   }
}
