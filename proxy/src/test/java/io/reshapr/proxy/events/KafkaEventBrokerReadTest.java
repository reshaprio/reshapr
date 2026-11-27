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

import io.reshapr.proxy.events.kafka.KafkaEventBroker;
import io.reshapr.proxy.events.kafka.KafkaPosition;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stateless read of the Kafka broker, against a mock consumer.
 * @author laurent
 */
class KafkaEventBrokerReadTest {

   private static final String TOPIC = "orders.created";

   private KafkaEventBroker broker(long beginning, String... payloads) {
      MockConsumer<String, String> consumer = new MockConsumer<>("earliest");
      TopicPartition tp = new TopicPartition(TOPIC, 0);
      consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
      consumer.updateBeginningOffsets(Map.of(tp, beginning));
      consumer.updateEndOffsets(Map.of(tp, beginning + payloads.length));
      consumer.schedulePollTask(() -> {
         for (int i = 0; i < payloads.length; i++) {
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, beginning + i, "k", payloads[i]));
         }
      });
      return new KafkaEventBroker(endpoint(), null, props -> consumer, props -> null, value -> value);
   }

   private static EventEndpoint endpoint() {
      return EventEndpoint.parse("kafka://broker:9092");
   }

   @Test
   void nullPositionReturnsEndOffsetsWithoutEvents() {
      EventBroker.ReadResult result = broker(0, "{}", "{}").read(
            new EventBroker.ReadRequest(TOPIC, null, null, 10, null));
      assertTrue(result.records().isEmpty());
      assertEquals("0:2", result.next().encode());
   }

   @Test
   void readsFromOffsetHonouringFilterAndMax() {
      EventBroker.ReadResult result = broker(0, "{\"org\":\"a\"}", "{\"org\":\"b\"}", "{\"org\":\"a\"}").read(
            new EventBroker.ReadRequest(TOPIC, new KafkaPosition(Map.of(0, 1L)), null, 1, EventFilter.parse("json:$.org==a")));
      assertEquals(1, result.records().size());
      assertEquals(TOPIC + "-0-2", result.records().get(0).eventId());
      assertEquals("0:3", result.next().encode());
      assertFalse(result.hasMore());
      assertFalse(result.truncated());
   }

   @Test
   void positionBeforeRetentionIsTruncated() {
      EventBroker.ReadResult result = broker(5, "{}", "{}").read(
            new EventBroker.ReadRequest(TOPIC, new KafkaPosition(Map.of(0, 1L)), null, 10, null));
      assertTrue(result.truncated());
      assertEquals(2, result.records().size());
      assertEquals(TOPIC + "-0-5", result.records().get(0).eventId());
   }

   @Test
   void readsReuseTheSameConsumer() {
      MockConsumer<String, String> consumer = new MockConsumer<>("earliest");
      TopicPartition tp = new TopicPartition(TOPIC, 0);
      consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
      consumer.updateBeginningOffsets(Map.of(tp, 0L));
      consumer.updateEndOffsets(Map.of(tp, 0L));
      AtomicInteger created = new AtomicInteger();
      KafkaEventBroker broker = new KafkaEventBroker(endpoint(), null, props -> {
         created.incrementAndGet();
         return consumer;
      }, props -> null, value -> value);

      for (int i = 0; i < 5; i++) {
         broker.read(new EventBroker.ReadRequest(TOPIC, null, null, 10, null));
      }
      assertEquals(1, created.get());
      assertFalse(consumer.closed());

      broker.close();
      assertTrue(consumer.closed());
   }
}
