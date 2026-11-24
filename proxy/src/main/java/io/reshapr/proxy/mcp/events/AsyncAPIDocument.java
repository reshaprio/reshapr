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

import io.reshapr.proxy.events.ChannelInfo;
import io.reshapr.json.ObjectMapperFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;

import java.util.Iterator;
import java.util.Map;

/**
 * Minimal Jackson-based view of an AsyncAPI 3.x document: the channel of each operation, as neutral
 * {@link ChannelInfo} (independent of MCP and of any broker). Internal {@code $ref} are inlined.
 * Protocol bindings are kept raw: they are interpreted by the {@link io.reshapr.proxy.events.EventBrokerFactory} of the protocol.
 * @author laurent
 */
public class AsyncAPIDocument {

   private static final ObjectMapper MAPPER = ObjectMapperFactory.getYamlObjectMapper();
   private static final int MAX_REF_DEPTH = 12;

   private final JsonNode root;

   private AsyncAPIDocument(JsonNode root) {
      this.root = root;
   }

   public static AsyncAPIDocument parse(String content) {
      try {
         return new AsyncAPIDocument(MAPPER.readTree(content));
      } catch (Exception e) {
         throw new IllegalArgumentException("Cannot parse AsyncAPI document: " + e.getMessage(), e);
      }
   }

   /** Resolve the channel an operation acts on, or null if unknown. */
   @Nullable
   public ChannelInfo getChannelForOperation(String operationName) {
      JsonNode operation = root.path("operations").get(operationName);
      if (operation == null) {
         return null;
      }
      String channelKey = lastSegment(operation.path("channel").path("$ref").asText(null));
      if (channelKey == null || !root.path("channels").has(channelKey)) {
         return null;
      }
      JsonNode channel = root.path("channels").get(channelKey);
      String address = channel.path("address").asText(channelKey);
      String description = operation.hasNonNull("summary") ? operation.get("summary").asText()
            : operation.has("description") ? operation.get("description").asText()
            : channel.has("description") ? channel.get("description").asText() : null;

      String messageName = null;
      ObjectNode payload = null;
      String contentType = root.path("defaultContentType").asText("application/json");
      Iterator<Map.Entry<String, JsonNode>> it = channel.path("messages").fields();
      if (it.hasNext()) {
         Map.Entry<String, JsonNode> first = it.next();
         JsonNode message = resolve(first.getValue());
         messageName = message.path("name").asText(first.getKey());
         contentType = message.path("contentType").asText(contentType);
         JsonNode payloadNode = message.get("payload");
         if (payloadNode != null && resolve(payloadNode) instanceof ObjectNode objectNode) {
            payload = objectNode;
         }
      }
      return new ChannelInfo(channelKey, address, description, messageName, payload, contentType,
            channel.has("bindings") ? channel.get("bindings") : null);
   }

   @Nullable
   private static String lastSegment(@Nullable String ref) {
      return ref == null ? null : ref.substring(ref.lastIndexOf('/') + 1);
   }

   private JsonNode resolve(JsonNode node) {
      return resolve(node, 0);
   }

   private JsonNode resolve(JsonNode node, int depth) {
      if (depth > MAX_REF_DEPTH || node == null) {
         return node;
      }
      if (node.isObject()) {
         JsonNode ref = node.get("$ref");
         if (ref != null && ref.isTextual() && ref.asText().startsWith("#/")) {
            JsonNode target = root.at(ref.asText().substring(1));
            return target.isMissingNode() ? node : resolve(target, depth + 1);
         }
         ObjectNode copy = JsonNodeFactory.instance.objectNode();
         node.fields().forEachRemaining(e -> copy.set(e.getKey(), resolve(e.getValue(), depth + 1)));
         return copy;
      }
      if (node.isArray()) {
         ArrayNode copy = JsonNodeFactory.instance.arrayNode();
         node.forEach(e -> copy.add(resolve(e, depth + 1)));
         return copy;
      }
      return node;
   }
}
