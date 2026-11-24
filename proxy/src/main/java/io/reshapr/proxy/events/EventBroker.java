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
package io.reshapr.proxy.events;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Transport SPI of the events feature: one instance is one connection (address and credentials) to a broker, created
 * lazily by an {@link EventBrokerFactory}. It holds no destination: topics, queues or paths are given on each call,
 * so that expositions sharing a connection never share destinations.
 * @author laurent
 */
public interface EventBroker {

   /** A running consumer. */
   interface EventConsumer extends AutoCloseable {
      @Override
      void close();
   }

   /** A producer bound to one endpoint. */
   interface EventProducer extends AutoCloseable {
      /** Publish a message to a destination. */
      PublishResult publish(String destination, @Nullable String key, String payload, Map<String, String> headers) throws Exception;

      @Override
      void close();
   }

   /**
    * Start consuming the given topics.
    * @param consumerName Identity of the consumer, unique per proxy replica and exposition: the broker maps it to its
    *                     own notion (Kafka consumer group, durable subscription or client name...).
    * @param destinations The topics, queues or paths to consume.
    */
   EventConsumer startConsumer(String consumerName, Set<String> destinations, Consumer<ConsumedRecord> sink);

   /** Read a topic without joining a consumer group, so that any replica can serve any cursor. */
   default ReadResult read(ReadRequest request) {
      throw new UnsupportedOperationException("Stateless read is not supported by this broker");
   }

   /**
    * Decode the token of a position previously produced by this technology.
    * @throws IllegalArgumentException if the token is malformed.
    */
   default EventPosition position(String token) {
      throw new UnsupportedOperationException("Positions are not supported by this broker");
   }

   /** Create a producer that would publish to specific topics. */
   EventProducer createProducer();

   /** Release the resources of the connection. */
   default void close() {
   }

   /**
    * A message read from a destination.
    * @param eventId Stable identifier assigned by the broker (e.g. Kafka {@code topic-partition-offset}), or null.
    * @param ref Broker-private data used by its {@link EventPosition}, or null.
    */
   record ConsumedRecord(String destination, @Nullable String eventId, EventMessage message, @Nullable Object ref) {
      public ConsumedRecord(String destination, EventMessage message) {
         this(destination, null, message, null);
      }

      /** The event id, generated when the broker gives none. */
      public String id() {
         return eventId != null ? eventId : java.util.UUID.randomUUID().toString();
      }
   }

   /**
    * A stateless, group-less read of one destination (MCP Events {@code events/poll}).
    * @param from Position to read from; null means "from now" (nothing is returned).
    * @param minTimestamp Epoch millis floor: older records are skipped (replay bound), or null.
    * @param maxRecords Maximum number of matching records to return.
    * @param filter Only matching payloads are returned (they still advance the position), or null.
    */
   record ReadRequest(String destination, @Nullable EventPosition from, @Nullable Long minTimestamp,
                      int maxRecords, @Nullable EventFilter filter) {
   }

   /**
    * Outcome of a {@link ReadRequest}.
    * @param next Position to resume from.
    * @param hasMore True when records remain beyond the returned batch.
    * @param truncated True when the requested position was no longer available (retention or replay bound).
    */
   record ReadResult(List<ConsumedRecord> records, EventPosition next, boolean hasMore, boolean truncated) {
   }

   /** Outcome of a publication; {@code id} is a broker-specific identifier of the message, or null. */
   record PublishResult(String destination, @Nullable String id) {
   }
}
