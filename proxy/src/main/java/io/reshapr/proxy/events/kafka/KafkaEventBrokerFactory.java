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
package io.reshapr.proxy.events.kafka;

import io.reshapr.proxy.events.*;
import io.reshapr.proxy.registry.SecretEntry;

import jakarta.annotation.Nullable;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * {@link EventBrokerFactory} of Kafka ({@code kafka://} endpoints).
 * @author laurent
 */
public class KafkaEventBrokerFactory implements EventBrokerFactory {

   private static final BrokerCapabilities CAPABILITIES = new BrokerCapabilities(true, true, true, false);

   @Override
   public String protocol() {
      return "kafka";
   }

   @Override
   public BrokerCapabilities capabilities() {
      return CAPABILITIES;
   }

   /** The Kafka binding {@code topic} when present, otherwise the channel address. */
   @Override
   public String destination(ChannelInfo channel) {
      if (channel.bindings() != null) {
         String topic = channel.bindings().path("kafka").path("topic").asText(null);
         if (topic != null && !topic.isBlank()) {
            return topic;
         }
      }
      return channel.address();
   }

   @Override
   public EventBroker create(EventEndpoint endpoint, @Nullable SecretEntry secret, UnaryOperator<String> secretResolver) {
      return new KafkaEventBroker(endpoint, secret, secretResolver);
   }
}
