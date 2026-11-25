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

import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Hub wired on a fake broker with an ASYNC exposition, shared by the events and MCP tests.
 * @author laurent
 */
public class EventHubFixture {

   public static final String SPEC = """
         asyncapi: 3.0.0
         info: {title: Orders, version: 1.0.0}
         servers:
           production: {host: kafka.example.com:9092, protocol: kafka}
         channels:
           orderCreated:
             address: orders.created
             messages:
               OrderCreated: {$ref: '#/components/messages/OrderCreated'}
           createOrderCommand:
             address: orders.commands.create
             messages:
               CreateOrder: {$ref: '#/components/messages/CreateOrder'}
         operations:
           onOrderCreated:
             action: send
             summary: Be notified when an order is created
             channel: {$ref: '#/channels/orderCreated'}
           createOrder:
             action: receive
             channel: {$ref: '#/channels/createOrderCommand'}
         components:
           messages:
             OrderCreated:
               payload: {type: object, properties: {orderId: {type: string}, org: {type: string}}}
             CreateOrder:
               payload: {type: object, required: [customerId], properties: {customerId: {type: string}}}
         """;

   public static class FakeBroker implements EventBroker {
      public final List<String> started = new ArrayList<>();
      public final List<String> published = new ArrayList<>();
      Consumer<ConsumedRecord> sink;
      public boolean closed;
      public boolean failPublish;

      @Override
      public EventConsumer startConsumer(String consumerName, Set<String> destinations, Consumer<ConsumedRecord> sink) {
         started.add(consumerName + ":" + new java.util.TreeSet<>(destinations));
         this.sink = sink;
         closed = false;
         return () -> closed = true;
      }

      @Override
      public EventProducer createProducer() {
         return new EventProducer() {
            @Override
            public PublishResult publish(String topic, String key, String payload, java.util.Map<String, String> headers) {
               if (failPublish) {
                  throw new IllegalStateException("broker down");
               }
               published.add(topic + "=" + payload);
               return new PublishResult(topic, "0-42");
            }

            @Override
            public void close() {
            }
         };
      }

      public void emit(String topic, String payload) {
         sink.accept(new ConsumedRecord(topic, new EventMessage(0, System.currentTimeMillis(), null, Map.of(), payload)));
      }
   }

   public final ObjectMapper mapper = new ObjectMapper();
   public FakeBroker broker;
   public GatewayRegistry registry;
   public EventHub hub;
   public ExpositionEntry exposition;

   public EventHubFixture(ChannelCatalog catalog) {
      broker = new FakeBroker();
      registry = new GatewayRegistry();
      hub = new EventHub(registry, new EventBrokerRegistry(List.of(new EventBrokerFactory() {
         @Override
         public String protocol() {
            return "kafka";
         }

         @Override
         public BrokerCapabilities capabilities() {
            return new BrokerCapabilities(true, true, true, false);
         }

         @Override
         public EventBroker create(EventEndpoint endpoint, SecretEntry secret, UnaryOperator<String> resolver) {
            return broker;
         }
      }), v -> v), catalog);
      hub.bufferSize = 10;
      hub.bufferTtlMillis = 60000;
      hub.graceMillis = 50;
      hub.configuredReplicaId = Optional.of("replica1");
      hub.gatewayId = "gw";
      hub.init();
      ServiceEntry service = new ServiceEntry("svc", "org", "Orders", "1.0.0", "ASYNC", List.of(
            new OperationEntry("onOrderCreated", "SEND", "orders.created", "OrderCreated", "OrderCreated"),
            new OperationEntry("createOrder", "RECEIVE", "orders.commands.create", "CreateOrder", "CreateOrder")));
      ConfigurationEntry config = new ConfigurationEntry("cfg", "cfg", "kafka://broker:9092?filter=json:$.org==foobar",
            null, List.of(), List.of(), null, null, null);
      ArtifactEntry artifact = new ArtifactEntry("art", "orders.yaml", "orders.yaml",
            ArtifactEntryType.ASYNC_API_SPEC, true, SPEC);
      exposition = new ExpositionEntry("expo", "orders", service, config, artifact, List.of());
      registry.addExposition(exposition);
   }
}
