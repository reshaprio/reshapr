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

import io.reshapr.proxy.registry.ExpositionEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the hub on top of a fake broker: subscription lifecycle, buffering, filter and publication.
 * @author laurent
 */
class EventHubTest {

   EventHubFixture fixture;
   EventHubFixture.FakeBroker broker;
   io.reshapr.proxy.registry.GatewayRegistry registry;
   EventHub hub;
   ExpositionEntry exposition;

   @BeforeEach
   void setUp() {
      fixture = new EventHubFixture(e -> List.of(new Channel("onOrderCreated", false,
            new ChannelInfo("orderCreated", "orders.created", null, "OrderCreated", null, "application/json", null))));
      broker = fixture.broker;
      registry = fixture.registry;
      hub = fixture.hub;
      exposition = fixture.exposition;
   }

   @Test
   void consumerStartsOnFirstSubscriptionWithPerReplicaGroupAndStopsAfterGrace() throws Exception {
      assertTrue(broker.started.isEmpty());

      EventSubscription sub = hub.subscribe(exposition, "alice", "1", null);
      assertEquals(List.of("replica1-expo:[orders.created]"), broker.started);

      EventSubscription sub2 = hub.subscribe(exposition, "bob", "1", null);
      assertEquals(1, broker.started.size());

      assertThrows(IllegalArgumentException.class, () -> hub.subscribe(exposition, "alice", "1", null));
      hub.unsubscribe(sub);
      hub.unsubscribe(sub2);
      Thread.sleep(300);
      assertTrue(broker.closed);
   }

   @Test
   void messagesAreFilteredNotifiedAndDeliveredOncePerSubscriber() throws Exception {
      EventSubscription sub = hub.subscribe(exposition, "alice", "1", null);
      broker.emit("orders.created", "{\"orderId\":\"a\",\"org\":\"other\"}");
      broker.emit("orders.created", "{\"orderId\":\"b\",\"org\":\"foobar\"}");
      broker.emit("orders.created", "{\"orderId\":\"c\",\"org\":\"foobar\"}");

      // Coalesced: one notification for the two matching messages.
      assertEquals(List.of("orderCreated"), sub.awaitUpdates(1, TimeUnit.SECONDS));

      String subscriber = hub.resolveSubscriber("expo", "orderCreated", "alice", "1");
      ChannelBuffer.ReadResult first = hub.read("expo", "orderCreated", subscriber);
      assertEquals(2, first.events().size());
      assertTrue(first.events().get(0).payload().contains("\"b\""));
      assertEquals(0, first.lostEvents());

      // Cursor advanced: nothing new on the second read.
      assertEquals(0, hub.read("expo", "orderCreated", subscriber).events().size());
   }

   @Test
   void overflowIsReportedAsLostEvents() throws Exception {
      hub.subscribe(exposition, "alice", "1", null);
      for (int i = 0; i < 15; i++) {
         broker.emit("orders.created", "{\"orderId\":\"" + i + "\",\"org\":\"foobar\"}");
      }
      ChannelBuffer.ReadResult result = hub.read("expo", "orderCreated", hub.resolveSubscriber("expo", "orderCreated", "alice", null));
      assertEquals(10, result.events().size());
      assertEquals(5, result.lostEvents());
   }

   @Test
   void changedExpositionClosesSubscriptions() throws Exception {
      EventSubscription sub = hub.subscribe(exposition, "alice", "1", null);
      registry.removeExposition("expo");
      hub.sweep();
      assertTrue(sub.isClosed());
      assertTrue(broker.closed);
   }

   @Test
   void publishSendsToTheReceiveChannelDestination() throws Exception {
      ChannelInfo channel = new ChannelInfo("createOrderCommand", "orders.commands.create", null, "CreateOrder", null, "application/json", null);
      EventHub.Receipt receipt = hub.publish(exposition, channel, null, "{\"customerId\":\"c1\"}");
      assertEquals(List.of("orders.commands.create={\"customerId\":\"c1\"}"), broker.published);
      assertEquals("orders.commands.create", receipt.destination());
      assertFalse(receipt.messageId().isBlank());
   }
}

