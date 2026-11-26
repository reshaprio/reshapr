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

import io.reshapr.proxy.events.EventHub;
import io.reshapr.proxy.events.EventStream;
import io.reshapr.proxy.events.EventSubscription;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ExpositionEntry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Handles the methods of the MCP Events extension ({@code events/list}, {@code events/poll},
 * {@code events/stream}) of an ASYNC exposition. It translates between the wire types of {@link McpEventsSchema}
 * and the transport-neutral {@link EventHub}; callers are responsible for checking that the request is eligible
 * (ASYNC exposition, modern protocol).
 * @author laurent
 */
@ApplicationScoped
public class McpEventsHandler {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final int POLL_DEFAULT_MAX = 100;
   private static final int POLL_CEILING = 500;
   private static final long POLL_IDLE_MS = 2000;

   private static final long HEARTBEAT_DEFAULT_MS = 30000;
   private static final long HEARTBEAT_MIN_MS = 1000;
   private static final long HEARTBEAT_MAX_MS = 60000;

   private final EventHub eventHub;
   private final WorkCache workCache;
   private final ObjectMapper mapper = new ObjectMapper();

   /**
    * Creates a new MCP Events handler with the given event hub and work cache.
    * @param eventHub the event hub to use for delivering events
    * @param workCache the work cache to use for caching references
    */
   public McpEventsHandler(EventHub eventHub, WorkCache workCache) {
      this.eventHub = eventHub;
      this.workCache = workCache;
   }

   /**
    * {@code events/list} handler: returns the list of event types exposed by the exposition.
    * @param request the JSON-RPC request
    * @param exposition the exposition entry defining the event types
    * @return the JSON-RPC response containing the list of event types
    */
   public McpSchema.JSONRPCResponse list(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      return result(request, new McpEventsSchema.ListResult(McpEventsSchema.RESULT_TYPE_COMPLETE,
            converter(exposition).listEventTypes()));
   }

   /**
    * {@code events/poll} handler: stateless, the cursor carries the broker positions.
    * @param request the JSON-RPC request
    * @param exposition the exposition entry defining this event type
    */
   public McpSchema.JSONRPCResponse poll(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      McpEventsSchema.Params params;
      try {
         params = getParams(request);
      } catch (IllegalArgumentException e) {
         return error(request, McpSchema.ErrorCodes.INVALID_PARAMS, e.getMessage(), null);
      }
      AsyncAPIMcpEventConverter converter = converter(exposition);
      McpEventsSchema.EventType type = findType(converter, params);
      if (type == null) {
         return unknownEvent(request, params);
      }
      if (!type.delivery().contains(McpEventsSchema.DELIVERY_POLL)) {
         return error(request, McpEventsSchema.ErrorCodes.EVENT_UNSUPPORTED,
               "Event type '" + type.name() + "' does not support poll", null);
      }
      int maxEvents = params.maxEvents() != null ? Math.max(1, Math.min(params.maxEvents(), POLL_CEILING))
            : POLL_DEFAULT_MAX;

      EventHub.PollResult polled;
      try {
         AsyncAPIMcpEventConverter.validateArguments(type, params.arguments());
         polled = eventHub.poll(exposition, type.name(), params.arguments(), params.cursor(), params.maxAgeMs(),
               maxEvents);
      } catch (IllegalArgumentException e) {
         return error(request, McpSchema.ErrorCodes.INVALID_PARAMS, e.getMessage(), null);
      } catch (RuntimeException e) {
         logger.warnf(e, "events/poll failed on exposition %s", exposition.id());
         return error(request, McpSchema.ErrorCodes.INTERNAL_ERROR, "Unable to read events from the broker", null);
      }

      List<McpEventsSchema.EventOccurrence> events = new ArrayList<>();
      for (EventHub.PolledEvent event : polled.events()) {
         events.add(converter.toOccurrence(type.name(), event.eventId(), event.timestamp(), event.payload(), null));
      }
      return result(request, new McpEventsSchema.PollResult(McpEventsSchema.RESULT_TYPE_COMPLETE, events,
            polled.cursor(), polled.truncated(), polled.hasMore(), polled.hasMore() ? 0 : POLL_IDLE_MS));
   }

   /**
    * {@code events/stream} handler: an SSE stream of {@code notifications/events/*} for one event type, resumable from a
    * cursor. Errors detected before the stream opens are plain JSON-RPC errors.
    * @param request the JSON-RPC request
    * @param exposition the exposition entry defining this event type
    * @param userId the authenticated user (or "anonymous")
    */
   public StreamOpening stream(McpSchema.JSONRPCRequest request, ExpositionEntry exposition, String userId) {
      McpEventsSchema.Params params;
      try {
         params = getParams(request);
      } catch (IllegalArgumentException e) {
         return failed(error(request, McpSchema.ErrorCodes.INVALID_PARAMS, e.getMessage(), null));
      }
      AsyncAPIMcpEventConverter converter = converter(exposition);
      McpEventsSchema.EventType type = findType(converter, params);
      if (type == null) {
         return failed(unknownEvent(request, params));
      }
      if (!type.delivery().contains(McpEventsSchema.DELIVERY_PUSH)) {
         return failed(error(request, McpEventsSchema.ErrorCodes.EVENT_UNSUPPORTED,
               "Event type '" + type.name() + "' does not support push", null));
      }
      long heartbeatMs = params.heartbeatMs() != null
            ? Math.max(HEARTBEAT_MIN_MS, Math.min(params.heartbeatMs(), HEARTBEAT_MAX_MS)) : HEARTBEAT_DEFAULT_MS;

      Map<String, Object> meta = request.id() != null
            ? Map.of(McpSchema.META_KEY_SUBSCRIPTION_ID, request.id())
            : Map.of();

      EventStream stream;
      try {
         AsyncAPIMcpEventConverter.validateArguments(type, params.arguments());
         stream = eventHub.openStream(exposition, type.name(), userId, String.valueOf(request.id()), params.arguments(), params.cursor());
      } catch (IllegalArgumentException e) {
         return failed(error(request, McpSchema.ErrorCodes.INVALID_PARAMS, e.getMessage(), null));
      } catch (IllegalStateException e) {
         return failed(error(request, McpEventsSchema.ErrorCodes.EVENT_RESOURCE_EXHAUSTED, e.getMessage(), null));
      }

      StreamingOutput output = out -> {
         try {
            stream.run(params.cursor(), params.maxAgeMs(), heartbeatMs, new EventStream.Sink() {
               @Override
               public void active(String cursor, boolean truncated, long hb) throws IOException {
                  writeNotification(out, McpEventsSchema.NOTIFICATION_ACTIVE,
                        new McpEventsSchema.ActiveNotification(cursor, truncated, hb, meta));
               }

               @Override
               public void event(String eventId, long timestamp, String payload, String cursor) throws IOException {
                  McpEventsSchema.EventOccurrence occurrence =
                        converter.toOccurrence(type.name(), eventId, timestamp, payload, cursor);
                  writeNotification(out, McpEventsSchema.NOTIFICATION_EVENT, new McpEventsSchema.EventNotification(
                        occurrence.eventId(), occurrence.name(), occurrence.timestamp(), occurrence.data(),
                        cursor, meta));
               }

               @Override
               public void heartbeat(String cursor) throws IOException {
                  writeNotification(out, McpEventsSchema.NOTIFICATION_HEARTBEAT,
                        new McpEventsSchema.HeartbeatNotification(cursor, meta));
               }

               @Override
               public void error(String message) throws IOException {
                  writeNotification(out, McpEventsSchema.NOTIFICATION_ERROR, new McpEventsSchema.ErrorNotification(
                        new McpEventsSchema.ErrorDetail(McpSchema.ErrorCodes.INTERNAL_ERROR, message, null), meta));
               }
            });
            // The server ended the subscription (exposition updated or removed).
            writeNotification(out, McpEventsSchema.NOTIFICATION_TERMINATED, new McpEventsSchema.ErrorNotification(
                  new McpEventsSchema.ErrorDetail(McpEventsSchema.ErrorCodes.EVENT_NOT_FOUND,
                        "Event type no longer available",
                        Map.of("kind", "event", "reason", "Exposition updated or removed")), meta));
            writeSse(out, mapper.writeValueAsString(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
                  request.id(), new McpEventsSchema.StreamResult(McpEventsSchema.RESULT_TYPE_COMPLETE, meta), null)));
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (IOException e) {
            logger.debugf("Event stream on '%s' closed by client", type.name());
         } finally {
            stream.close();
         }
      };
      return new StreamOpening(null, output);
   }

   /**
    * {@code subscriptions/listen}: an SSE stream that acknowledges the subscribed event channels, then pushes
    * {@code notifications/resources/updated} each time one of them receives events, until the client disconnects or the
    * exposition goes away. Errors detected before the stream opens are plain JSON-RPC errors.
    */
   public StreamOpening listen(McpSchema.JSONRPCRequest request, ExpositionEntry exposition, String userId) {
      // Requested resource URIs: null means every event channel.
      Set<String> channelKeys = null;
      if (request.params() instanceof Map<?, ?> params && params.get("notifications") instanceof Map<?, ?> notifications
            && notifications.get("resourceSubscriptions") instanceof List<?> uris) {
         channelKeys = new HashSet<>();
         for (Object uri : uris) {
            String key = AsyncAPIChannels.channelKeyFromUri(String.valueOf(uri));
            if (key != null) {
               channelKeys.add(key);
            }
         }
      }

      EventSubscription subscription;
      try {
         subscription = eventHub.subscribe(exposition, userId, String.valueOf(request.id()), channelKeys);
      } catch (IllegalArgumentException e) {
         return failed(error(request, McpSchema.ErrorCodes.INVALID_PARAMS, e.getMessage(), null));
      }

      Map<String, Object> meta = Map.of(McpSchema.META_KEY_SUBSCRIPTION_ID, request.id());
      List<String> acknowledgedUris = subscription.channelKeys().stream().map(AsyncAPIChannels::uri).sorted().toList();

      StreamingOutput output = out -> {
         try {
            writeNotification(out, McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED,
                  Map.of("_meta", meta, "notifications", Map.of("resourceSubscriptions", acknowledgedUris)));
            while (!subscription.isClosed()) {
               List<String> updated = subscription.awaitUpdates(15, TimeUnit.SECONDS);
               if (updated.isEmpty()) {
                  if (subscription.isClosed()) {
                     break;
                  }
                  out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  continue;
               }
               for (String updatedKey : updated) {
                  writeNotification(out, McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
                        Map.of("_meta", meta, "uri", AsyncAPIChannels.uri(updatedKey)));
               }
            }
            // The server ended the subscription: signal a graceful closure.
            writeSse(out, mapper.writeValueAsString(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
                  request.id(), Map.of("resultType", "complete", "_meta", meta), null)));
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (IOException e) {
            logger.debugf("Event subscription %s closed by client", subscription.key());
         } finally {
            eventHub.unsubscribe(subscription);
         }
      };
      return new StreamOpening(null, output);
   }

   /** Outcome of opening a stream: either a JSON-RPC error (nothing was opened), or the SSE body to send. */
   public record StreamOpening(@Nullable McpSchema.JSONRPCResponse error, @Nullable StreamingOutput output) {
   }

   private AsyncAPIMcpEventConverter converter(ExpositionEntry exposition) {
      return new AsyncAPIMcpEventConverter(exposition, workCache, mapper, eventHub.capabilities(exposition));
   }

   private McpEventsSchema.Params getParams(McpSchema.JSONRPCRequest request) {
      McpEventsSchema.Params params = mapper.convertValue(request.params(), McpEventsSchema.Params.class);
      return params != null ? params : new McpEventsSchema.Params(null, null, null, null, null, null);
   }

   @Nullable
   private static McpEventsSchema.EventType findType(AsyncAPIMcpEventConverter converter, McpEventsSchema.Params params) {
      return params.name() == null ? null : converter.findEventType(params.name());
   }

   private static McpSchema.JSONRPCResponse unknownEvent(McpSchema.JSONRPCRequest request, McpEventsSchema.Params params) {
      return error(request, McpEventsSchema.ErrorCodes.EVENT_NOT_FOUND,
            "Unknown event type" + (params.name() == null ? "" : " '" + params.name() + "'"), Map.of("kind", "event"));
   }

   private static StreamOpening failed(McpSchema.JSONRPCResponse error) {
      return new StreamOpening(error, null);
   }

   private static McpSchema.JSONRPCResponse result(McpSchema.JSONRPCRequest request, Object result) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
   }

   private static McpSchema.JSONRPCResponse error(McpSchema.JSONRPCRequest request, int code, String message,
                                                  @Nullable Object data) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
            new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
   }

   private void writeNotification(OutputStream out, String method, Object params) throws IOException {
      writeSse(out, mapper.writeValueAsString(
            new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, method,
                  mapper.convertValue(params, new TypeReference<Map<String, Object>>() {
                  }))));
   }

   private static void writeSse(OutputStream output, String json) throws IOException {
      output.write(("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
   }
}
