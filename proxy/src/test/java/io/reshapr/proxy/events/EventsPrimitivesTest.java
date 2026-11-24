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
import io.reshapr.proxy.events.kafka.KafkaEventBrokerFactory;
import io.reshapr.proxy.events.kafka.KafkaPosition;
import io.reshapr.proxy.registry.SecretEntry;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case for the primitives of the events package: buffers, filters, endpoints, cursors.
 * @author laurent
 */
class EventsPrimitivesTest {

   @Test
   void bufferKeepsReadMessagesUntilSizeOrTtl() {
      ChannelBuffer buffer = new ChannelBuffer(100, 60000);
      buffer.addSubscriber("a");
      buffer.addSubscriber("b");
      buffer.append(new EventMessage(0, System.currentTimeMillis(), null, Map.of(), "m1"));
      assertEquals(1, buffer.read("a").events().size());
      assertEquals(1, buffer.size());
      assertEquals(1, buffer.read("b").events().size());
      assertEquals(1, buffer.size());
      ChannelBuffer small = new ChannelBuffer(2, 60000);
      for (int i = 0; i < 3; i++) {
         small.append(new EventMessage(0, System.currentTimeMillis(), null, Map.of(), "m" + i));
      }
      assertEquals(2, small.size());
   }

   @Test
   void newSubscriberDoesNotSeeHistory() {
      ChannelBuffer buffer = new ChannelBuffer(100, 60000);
      buffer.addSubscriber("a");
      buffer.append(new EventMessage(0, System.currentTimeMillis(), null, Map.of(), "m1"));
      buffer.addSubscriber("late");
      assertTrue(buffer.read("late").events().isEmpty());
      assertEquals(1, buffer.read("a").events().size());
   }

   @Test
   void endpointParsing() {
      EventEndpoint endpoint = EventEndpoint.parse("kafka://b1:9092,b2:9092/my-topic?filter=json:$.org==foobar&saslMechanism=SCRAM-SHA-512");
      assertEquals("kafka", endpoint.protocol());
      assertEquals("b1:9092,b2:9092", endpoint.server());
      assertEquals("my-topic", endpoint.destination());
      assertEquals("SCRAM-SHA-512", endpoint.options().get("saslMechanism"));
      assertTrue(endpoint.filter().matches("{\"org\":\"foobar\"}"));
      assertNull(EventEndpoint.parse("kafka://b:9092").destination());
   }

   @Test
   void filterSupportsEqualsAndNotEquals() {
      EventFilter eq = EventFilter.parse("json:$.org==foobar");
      assertTrue(eq.matches("{\"org\":\"foobar\"}"));
      assertFalse(eq.matches("{\"org\":\"x\"}"));
      assertFalse(eq.matches("not json"));
      EventFilter ne = EventFilter.parse("json:$.a.b!=1");
      assertTrue(ne.matches("{\"a\":{\"b\":2}}"));
      assertFalse(ne.matches("{\"a\":{\"b\":1}}"));
      assertThrows(IllegalArgumentException.class, () -> EventFilter.parse("xpath://a"));
   }

   @Test
   void kafkaBrokerConsumesWithManualCommitAndProducesSasl() throws Exception {
      MockConsumer<String, String> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
      List<Properties> consumerProps = new CopyOnWriteArrayList<>();
      SecretEntry secret = new SecretEntry("s", "user", "pass", null, null, null, false, null);
      KafkaEventBroker broker = new KafkaEventBroker(EventEndpoint.parse("kafka://b:9092"), secret, props -> {
         consumerProps.add(props);
         return mock;
      }, props -> null, value -> value);

      CountDownLatch received = new CountDownLatch(1);
      List<EventBroker.ConsumedRecord> records = new CopyOnWriteArrayList<>();
      TopicPartition tp = new TopicPartition("orders.created", 0);
      mock.schedulePollTask(() -> {
         mock.rebalance(List.of(tp));
         mock.updateBeginningOffsets(Map.of(tp, 0L));
         mock.addRecord(new ConsumerRecord<>("orders.created", 0, 0L, "k", "{\"x\":1}"));
      });
      EventBroker.EventConsumer consumer = broker.startConsumer(
            "replica-expo", Set.of("orders.created"), r -> {
               records.add(r);
               received.countDown();
            });
      assertTrue(received.await(5, TimeUnit.SECONDS));
      consumer.close();

      assertEquals("{\"x\":1}", records.getFirst().message().payload());
      assertEquals("k", records.getFirst().message().key());
      Properties props = consumerProps.getFirst();
      assertEquals("replica-expo", props.get("group.id"));
      assertEquals("false", props.get("enable.auto.commit"));
      assertEquals("SASL_SSL", props.get("security.protocol"));
      assertTrue(props.get("sasl.jaas.config").toString().contains("username=\"user\""));
   }

   @Test
   void kafkaBindingTopicOverridesChannelAddress() throws Exception {
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      ChannelInfo bound = new ChannelInfo("orderCreated", "orders.created", null, null, null, "application/json",
            mapper.readTree("{\"kafka\":{\"topic\":\"prod-orders-v2\"}}"));
      ChannelInfo plain = new ChannelInfo("plain", "plain.topic", null, null, null, "application/json", null);
      KafkaEventBrokerFactory kafka = new KafkaEventBrokerFactory();
      assertEquals("prod-orders-v2", kafka.destination(bound));
      assertEquals("plain.topic", kafka.destination(plain));
   }

   @Test
   void kafkaPositionRoundTripsAndTracksContinuity() {
      KafkaPosition position = KafkaPosition.parse("0:5,1:9");
      assertEquals("0:5,1:9", position.encode());
      EventBroker.ConsumedRecord next = new EventBroker.ConsumedRecord("t", "t-0-5", null, new KafkaPosition.Ref(0, 5));
      EventBroker.ConsumedRecord old = new EventBroker.ConsumedRecord("t", "t-0-4", null, new KafkaPosition.Ref(0, 4));
      EventBroker.ConsumedRecord gap = new EventBroker.ConsumedRecord("t", "t-1-12", null, new KafkaPosition.Ref(1, 12));
      assertEquals(EventPosition.Continuity.NEXT, position.check(next));
      assertEquals(EventPosition.Continuity.DUPLICATE, position.check(old));
      assertEquals(EventPosition.Continuity.GAP, position.check(gap));
      assertEquals("0:6,1:9", position.advance(next).encode());
      assertThrows(IllegalArgumentException.class, () -> KafkaPosition.parse("0-5"));
   }

   @Test
   void filtersCombineEndpointAndArguments() {
      EventFilter both = EventFilter.and(EventFilter.parse("json:$.org==foobar"),
            EventFilter.ofArguments(java.util.Map.of("region", "eu")));
      assertTrue(both.matches("{\"org\":\"foobar\",\"region\":\"eu\"}"));
      assertFalse(both.matches("{\"org\":\"foobar\",\"region\":\"us\"}"));
      assertFalse(both.matches("{\"org\":\"x\",\"region\":\"eu\"}"));
      assertNull(EventFilter.and(null, EventFilter.ofArguments(java.util.Map.of())));
   }

   @Test
   void cursorRoundTripsAndIsBoundToItsTopic() {
      String cursor = EventCursor.encode("t1", "0:5,1:9");
      assertEquals("0:5,1:9", EventCursor.decode(cursor, "t1"));
      assertNull(EventCursor.decode(null, "t1"));
      assertThrows(IllegalArgumentException.class, () -> EventCursor.decode(cursor, "t2"));
      assertThrows(IllegalArgumentException.class, () -> EventCursor.decode("garbage", "t1"));
   }
}
