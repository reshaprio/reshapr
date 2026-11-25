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

import io.reshapr.proxy.mcp.McpSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Types of the MCP Events extension ({@code io.modelcontextprotocol/events}, protocol 2026-07-28 and later),
 * the equivalent of {@link McpSchema} for the extension. They only mirror the wire format: building them from an
 * AsyncAPI document is the job of the converter, and delivering events the one of the hub.
 * @author laurent
 */
public final class McpEventsSchema {

   private McpEventsSchema() {
   }

   public static final String EXTENSION_EVENTS = "io.modelcontextprotocol/events";

   public static final String METHOD_EVENTS_LIST = "events/list";
   public static final String METHOD_EVENTS_POLL = "events/poll";
   public static final String METHOD_EVENTS_STREAM = "events/stream";

   public static final String NOTIFICATION_ACTIVE = "notifications/events/active";
   public static final String NOTIFICATION_EVENT = "notifications/events/event";
   public static final String NOTIFICATION_HEARTBEAT = "notifications/events/heartbeat";
   public static final String NOTIFICATION_ERROR = "notifications/events/error";
   public static final String NOTIFICATION_TERMINATED = "notifications/events/terminated";

   public static final String DELIVERY_POLL = "poll";
   public static final String DELIVERY_PUSH = "push";

   public static final String RESULT_TYPE_COMPLETE = "complete";

   /** Error codes of the extension. */
   public static final class ErrorCodes {

      private ErrorCodes() {
      }

      /** Unknown event type or subscription; the error {@code data.kind} is {@code event} or {@code subscription}. */
      public static final int EVENT_NOT_FOUND = -32023;

      /** The caller is not allowed to receive this event type. */
      public static final int EVENT_FORBIDDEN = -32024;

      /** A server-side limit (streams, subscriptions) is reached. */
      public static final int EVENT_RESOURCE_EXHAUSTED = -32025;

      /** The requested delivery mode is not offered for this event type. */
      public static final int EVENT_UNSUPPORTED = -32026;
   }

   /**
    * An event type as listed by {@code events/list}.
    * @param name Event name.
    * @param description Human-readable description.
    * @param delivery Delivery modes offered ({@code poll}, {@code push}).
    * @param inputSchema JSON schema of the subscription arguments.
    * @param payloadSchema JSON schema of the event data, when known.
    */
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record EventType(@JsonProperty("name") String name,
                           @JsonProperty("description") String description,
                           @JsonProperty("delivery") List<String> delivery,
                           @JsonProperty("inputSchema") ObjectNode inputSchema,
                           @JsonProperty("payloadSchema") @Nullable ObjectNode payloadSchema) {
   }

   /** Result of {@code events/list}. */
   public record ListResult(@JsonProperty("resultType") String resultType,
                            @JsonProperty("events") List<EventType> events) {
   }

   /** Params shared by {@code events/poll} and {@code events/stream}; absent fields are null. */
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Params(@JsonProperty("name") @Nullable String name,
                        @JsonProperty("arguments") @Nullable Map<String, Object> arguments,
                        @JsonProperty("cursor") @Nullable String cursor,
                        @JsonProperty("maxAgeMs") @Nullable Long maxAgeMs,
                        @JsonProperty("maxEvents") @Nullable Integer maxEvents,
                        @JsonProperty("heartbeatMs") @Nullable Long heartbeatMs) {
   }

   /** An event occurrence; the cursor is only present on streamed events. */
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record EventOccurrence(@JsonProperty("eventId") String eventId,
                                 @JsonProperty("name") String name,
                                 @JsonProperty("timestamp") String timestamp,
                                 @JsonProperty("data") Object data,
                                 @JsonProperty("cursor") @Nullable String cursor) {
   }

   /** Result of {@code events/poll}. */
   public record PollResult(@JsonProperty("resultType") String resultType,
                            @JsonProperty("events") List<EventOccurrence> events,
                            @JsonProperty("cursor") String cursor,
                            @JsonProperty("truncated") boolean truncated,
                            @JsonProperty("hasMore") boolean hasMore,
                            @JsonProperty("nextPollMs") long nextPollMs) {
   }

   /** Params of {@code notifications/events/active}. */
   public record ActiveNotification(@JsonProperty("cursor") String cursor,
                                    @JsonProperty("truncated") boolean truncated,
                                    @JsonProperty("heartbeatMs") long heartbeatMs,
                                    @JsonProperty("_meta") Map<String, Object> meta) {
   }

   /** Params of {@code notifications/events/event}. */
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record EventNotification(@JsonProperty("eventId") String eventId,
                                   @JsonProperty("name") String name,
                                   @JsonProperty("timestamp") String timestamp,
                                   @JsonProperty("data") Object data,
                                   @JsonProperty("cursor") String cursor,
                                   @JsonProperty("_meta") Map<String, Object> meta) {
   }

   /** Params of {@code notifications/events/heartbeat}. */
   public record HeartbeatNotification(@JsonProperty("cursor") String cursor,
                                       @JsonProperty("_meta") Map<String, Object> meta) {
   }

   /** Error carried by {@code notifications/events/error} (transient) and {@code .../terminated}. */
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record ErrorDetail(@JsonProperty("code") int code,
                             @JsonProperty("message") String message,
                             @JsonProperty("data") @Nullable Object data) {
   }

   /** Params of {@code notifications/events/error} and {@code notifications/events/terminated}. */
   public record ErrorNotification(@JsonProperty("error") ErrorDetail error,
                                   @JsonProperty("_meta") Map<String, Object> meta) {
   }

   /** Final result of an {@code events/stream} request. */
   public record StreamResult(@JsonProperty("resultType") String resultType,
                              @JsonProperty("_meta") Map<String, Object> meta) {
   }
}
