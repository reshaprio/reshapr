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

import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.UnaryOperator;

/**
 * Test factory overriding the Kafka one for the {@code kafka} protocol.
 * @author laurent
 */
@ApplicationScoped
public class FakeEventBrokerFactory implements EventBrokerFactory {

   @Override
   public String protocol() {
      return "kafka";
   }

   @Override
   public BrokerCapabilities capabilities() {
      return new BrokerCapabilities(true, true, true, false);
   }

   @Override
   public EventBroker create(EventEndpoint endpoint, SecretEntry secret, UnaryOperator<String> secretResolver) {
      return new FakeEventBroker();
   }
}
