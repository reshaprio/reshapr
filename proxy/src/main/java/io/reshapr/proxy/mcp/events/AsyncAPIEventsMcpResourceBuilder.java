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
import io.reshapr.proxy.events.ChannelBuffer;
import io.reshapr.proxy.events.EventHub;
import io.reshapr.proxy.events.EventMessage;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.McpResourceBuilder;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exposes the AsyncAPI {@code send} channels of an exposition as MCP resources
 * ({@code events://{channelKey}}), on top of the regular Reshapr resources.
 * <p>
 * {@code notifications/resources/updated} only says that a channel has news; the messages are delivered by
 * {@code resources/read}, which returns everything received since the previous read of the same subscriber
 * and advances its cursor (at-most-once).
 * @author laurent
 */
public class AsyncAPIEventsMcpResourceBuilder implements McpResourceBuilder {

   private final ExpositionEntry exposition;
   private final McpResourceBuilder delegate;
   private final WorkCache workCache;
   private final ObjectMapper mapper;
   private final EventHub eventHub;
   private final String userId;
   private final String subscriptionId;

   /**
    * @param delegate The regular resource builder, consulted for non-event URIs.
    * @param userId The authenticated user (or "anonymous").
    * @param subscriptionId The subscription the caller says it reads for, or null to use the user's subscription.
    */
   public AsyncAPIEventsMcpResourceBuilder(ExpositionEntry exposition, McpResourceBuilder delegate,
                                           WorkCache workCache, ObjectMapper mapper, EventHub eventHub,
                                           String userId, @Nullable String subscriptionId) {
      this.exposition = exposition;
      this.delegate = delegate;
      this.workCache = workCache;
      this.mapper = mapper;
      this.eventHub = eventHub;
      this.userId = userId;
      this.subscriptionId = subscriptionId;
   }

   @Override
   public List<McpSchema.Resource> listResources() {
      List<McpSchema.Resource> resources = new ArrayList<>(delegate.listResources());
      for (Channel channel : AsyncAPIChannels.channels(exposition, workCache, false)) {
         String description = channel.info().description() != null ? channel.info().description()
               : "Events published on '" + channel.info().address() + "'";
         resources.add(new McpSchema.Resource(AsyncAPIChannels.uri(channel.info().key()), channel.info().key(), channel.info().key(),
               description + ". Subscribe with subscriptions/listen, then read to get the new events.",
               "application/json", null, null, null));
      }
      return resources;
   }

   @Override
   public List<McpSchema.ResourceTemplate> listResourceTemplates() {
      return delegate.listResourceTemplates();
   }

   @Override
   @Nullable
   public List<McpSchema.ResourceContents> readResource(McpSchema.ReadResourceRequest request,
                                                        ConfigurationEntry configuration) {
      String channelKey = AsyncAPIChannels.channelKeyFromUri(request.uri());
      if (channelKey == null) {
         return delegate.readResource(request, configuration);
      }
      Channel channel = AsyncAPIChannels.channels(exposition, workCache, false).stream()
            .filter(c -> c.info().key().equals(channelKey)).findFirst().orElse(null);
      if (channel == null) {
         return null;
      }

      String subscriberKey = eventHub.resolveSubscriber(exposition.id(), channelKey, userId, subscriptionId);
      ChannelBuffer.ReadResult result = subscriberKey == null ? null
            : eventHub.read(exposition.id(), channelKey, subscriberKey);

      ObjectNode body = mapper.createObjectNode();
      ArrayNode events = body.putArray("events");
      if (result != null) {
         for (EventMessage message : result.events()) {
            ObjectNode event = events.addObject();
            event.put("sequence", message.sequence());
            event.put("timestamp", message.timestamp());
            if (message.key() != null) {
               event.put("key", message.key());
            }
            if (!message.headers().isEmpty()) {
               event.set("headers", mapper.valueToTree(message.headers()));
            }
            String payload = message.payload();
            try {
               event.set("payload", mapper.readTree(payload));
            } catch (Exception e) {
               event.put("payload", payload);
            }
         }
         body.put("lostEvents", result.lostEvents());
      } else {
         body.put("lostEvents", 0);
         body.put("subscribed", false);
      }

      try {
         return List.of(new McpSchema.TextResourceContents(request.uri(), "application/json",
               mapper.writeValueAsString(body), Map.of()));
      } catch (Exception e) {
         throw new IllegalStateException("Cannot serialize events", e);
      }
   }
}
