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

import io.reshapr.proxy.events.kafka.KafkaPosition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test replacement of the Kafka broker, letting tests emit messages and inspect publications.
 * @author laurent
 */
public class FakeEventBroker implements EventBroker {

   public static final List<String> PUBLISHED = new CopyOnWriteArrayList<>();
   public static volatile Consumer<ConsumedRecord> sink;

   public static void emit(String topic, String payload) {
      EventMessage message = new EventMessage(0, System.currentTimeMillis(), null, Map.of(), payload);
      sink.accept(new ConsumedRecord(topic, message));
   }

   @Override
   public EventConsumer startConsumer(String consumerName, Set<String> destinations, Consumer<ConsumedRecord> sink) {
      FakeEventBroker.sink = sink;
      return () -> FakeEventBroker.sink = null;
   }

   /** In-memory log of single-partition topics, for stateless reads. */
   public static final Map<String, List<EventMessage>> LOG = new java.util.concurrent.ConcurrentHashMap<>();

   @Override
   public ReadResult read(ReadRequest request) {
      List<EventMessage> log = LOG.getOrDefault(request.destination(), List.of());
      if (request.from() == null) {
         return new ReadResult(List.of(), new KafkaPosition(Map.of(0, (long) log.size())), false, false);
      }
      long start = ((KafkaPosition) request.from()).offsets().getOrDefault(0, 0L);
      boolean truncated = start > log.size();
      start = Math.min(start, log.size());
      List<ConsumedRecord> records = new java.util.ArrayList<>();
      long position = start;
      while (position < log.size() && records.size() < request.maxRecords()) {
         EventMessage message = log.get((int) position);
         if (request.filter() == null || request.filter().matches(message.payload())) {
            records.add(new ConsumedRecord(request.destination(), request.destination() + "-0-" + position, message,
                  new KafkaPosition.Ref(0, position)));
         }
         position++;
      }
      return new ReadResult(records, new KafkaPosition(Map.of(0, position)), position < log.size(), truncated);
   }

   @Override
   public EventPosition position(String token) {
      return KafkaPosition.parse(token);
   }

   @Override
   public EventProducer createProducer() {
      return new EventProducer() {
         @Override
         public PublishResult publish(String topic, String key, String payload, Map<String, String> headers) {
            PUBLISHED.add(topic + "=" + payload);
            return new PublishResult(topic, "1-7");
         }

         @Override
         public void close() {
         }
      };
   }
}
