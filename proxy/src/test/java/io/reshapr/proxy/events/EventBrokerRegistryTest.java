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

import io.reshapr.proxy.registry.SecretEntry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test case for the {@link EventBrokerRegistry}, which manages the lifecycle of brokers and their expositions.
 * @author laurent
 */
class EventBrokerRegistryTest {

   static class CountingBroker implements EventBroker {
      boolean closed;

      @Override
      public EventConsumer startConsumer(String consumerName, Set<String> destinations, Consumer<ConsumedRecord> sink) {
         return () -> {
         };
      }

      @Override
      public EventProducer createProducer() {
         return null;
      }

      @Override
      public void close() {
         closed = true;
      }
   }

   final List<CountingBroker> created = new ArrayList<>();
   final EventBrokerRegistry registry = new EventBrokerRegistry(List.of(new EventBrokerFactory() {
      @Override
      public String protocol() {
         return "kafka";
      }

      @Override
      public BrokerCapabilities capabilities() {
         return new BrokerCapabilities(true, false, false, false);
      }

      @Override
      public EventBroker create(EventEndpoint endpoint, SecretEntry secret, UnaryOperator<String> resolver) {
         CountingBroker broker = new CountingBroker();
         created.add(broker);
         return broker;
      }
   }), v -> v);

   @Test
   void capabilitiesAreKnownWithoutCreatingAnyBroker() {
      assertTrue(registry.capabilities("kafka").replay());
      assertEquals(BrokerCapabilities.NONE, registry.capabilities("sqs"));
      assertTrue(created.isEmpty());
   }

   @Test
   void expositionsShareAConnectionButNotItsDestination() {
      EventBroker a = registry.acquire("a", EventEndpoint.parse("kafka://b:9092/topic-a?filter=json:$.x==1"), null);
      EventBroker b = registry.acquire("b", EventEndpoint.parse("kafka://b:9092/topic-b"), null);
      EventBroker other = registry.acquire("c", EventEndpoint.parse("kafka://other:9092"), null);
      assertSame(a, b);
      assertNotSame(a, other);
      assertEquals(2, created.size());

      SecretEntry secret = new SecretEntry("s", "user", "pass", null, null, null, false, null);
      assertNotSame(a, registry.acquire("d", EventEndpoint.parse("kafka://b:9092"), secret));
   }

   @Test
   void brokerIsClosedWhenLastExpositionReleasesAndFollowsEndpointChanges() {
      registry.acquire("a", EventEndpoint.parse("kafka://b:9092"), null);
      registry.acquire("b", EventEndpoint.parse("kafka://b:9092"), null);
      registry.release("a");
      assertTrue(!created.getFirst().closed);
      // "b" moves to another cluster: the first connection has no user left.
      registry.acquire("b", EventEndpoint.parse("kafka://c:9092"), null);
      assertTrue(created.getFirst().closed);
      assertTrue(!created.get(1).closed);
   }

   @Test
   void unsupportedProtocolIsRejected() {
      assertThrows(IllegalArgumentException.class, () -> registry.acquire("a", EventEndpoint.parse("sqs://queue"), null));
   }
}
