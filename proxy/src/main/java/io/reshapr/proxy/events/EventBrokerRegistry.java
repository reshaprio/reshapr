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
import io.reshapr.proxy.secret.SecretReferenceResolver;

import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves the {@link EventBroker} of an exposition from its endpoint, creating it lazily on first use.
 * A broker is a connection: expositions share one when scheme, server, options and secret are the same,
 * and only then. Destinations (topics, queues, paths) are not part of the key, nor held by the broker, so sharing a
 * connection never mixes the destinations of two expositions.
 * Brokers are reference-counted by exposition and closed when the last one lets go.
 * @author laurent
 */
@ApplicationScoped
public class EventBrokerRegistry {

   private static final Logger logger = Logger.getLogger(EventBrokerRegistry.class);

   /** Identity of a connection. Holds the secret: never log it. */
   record ConnectionKey(String protocol, String server, Map<String, String> options,
                        @Nullable SecretEntry secret) {
      @Override
      public String toString() {
         return protocol + "://" + server;
      }
   }

   private static final class Entry {
      final EventBroker broker;
      final Set<String> expositions = new HashSet<>();
      EventBroker.EventProducer producer;

      Entry(EventBroker broker) {
         this.broker = broker;
      }

      void close() {
         if (producer != null) {
            producer.close();
         }
         broker.close();
      }
   }

   private final Map<String, EventBrokerFactory> factories = new HashMap<>();
   private final Function<String, String> secretResolver;
   private final Map<ConnectionKey, Entry> entries = new HashMap<>();

   @Inject
   public EventBrokerRegistry(Instance<EventBrokerFactory> beans, SecretReferenceResolver resolver) {
      this(beans.stream().toList(), resolver::resolve);
   }

   /** Factories found by {@link ServiceLoader} are overridden by the given ones for the same protocol. */
   public EventBrokerRegistry(Collection<EventBrokerFactory> overrides, Function<String, String> secretResolver) {
      this.secretResolver = secretResolver;
      for (EventBrokerFactory factory : ServiceLoader.load(EventBrokerFactory.class)) {
         factories.put(factory.protocol(), factory);
      }
      for (EventBrokerFactory factory : overrides) {
         factories.put(factory.protocol(), factory);
      }
   }

   /** Capabilities of the technology behind a protocol, without connecting; none when it is not supported. */
   public BrokerCapabilities capabilities(String protocol) {
      EventBrokerFactory factory = factories.get(protocol);
      return factory == null ? BrokerCapabilities.NONE : factory.capabilities();
   }

   /** The destination of a channel on a protocol, from its factory; the channel address when unsupported. */
   public String destination(String protocol, ChannelInfo channel) {
      EventBrokerFactory factory = factories.get(protocol);
      return factory == null ? channel.address() : factory.destination(channel);
   }

   /**
    * The broker of the exposition, created on first use. Moves the exposition away from any other connection
    * it was previously attached to.
    */
   public synchronized EventBroker acquire(String expositionId, EventEndpoint endpoint, @Nullable SecretEntry secret) {
      return entry(expositionId, endpoint, secret).broker;
   }

   /** The shared producer of the exposition's connection, created on first use. */
   public synchronized EventBroker.EventProducer producer(String expositionId, EventEndpoint endpoint,
                                                          @Nullable SecretEntry secret) {
      Entry entry = entry(expositionId, endpoint, secret);
      if (entry.producer == null) {
         entry.producer = entry.broker.createProducer();
      }
      return entry.producer;
   }

   /** The exposition no longer needs a broker; connections nobody uses are closed. */
   public synchronized void release(String expositionId) {
      detach(expositionId, null);
   }

   @PreDestroy
   synchronized void shutdown() {
      entries.values().forEach(Entry::close);
      entries.clear();
   }

   private Entry entry(String expositionId, EventEndpoint endpoint, @Nullable SecretEntry secret) {
      EventBrokerFactory factory = factories.get(endpoint.protocol());
      if (factory == null) {
         throw new IllegalArgumentException("No event broker for protocol '" + endpoint.protocol() + "'");
      }
      ConnectionKey key = new ConnectionKey(endpoint.protocol(), endpoint.server(), endpoint.options(), secret);
      detach(expositionId, key);
      Entry entry = entries.get(key);
      if (entry == null) {
         logger.infof("Creating event broker for %s", key);
         entry = new Entry(factory.create(endpoint, secret, secretResolver));
         entries.put(key, entry);
      }
      entry.expositions.add(expositionId);
      return entry;
   }

   private void detach(String expositionId, @Nullable ConnectionKey except) {
      for (Iterator<Map.Entry<ConnectionKey, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<ConnectionKey, Entry> candidate = it.next();
         if (candidate.getKey().equals(except)) {
            continue;
         }
         if (candidate.getValue().expositions.remove(expositionId) && candidate.getValue().expositions.isEmpty()) {
            logger.infof("Closing event broker for %s", candidate.getKey());
            candidate.getValue().close();
            it.remove();
         }
      }
   }
}
