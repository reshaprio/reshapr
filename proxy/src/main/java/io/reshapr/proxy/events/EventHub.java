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
import io.reshapr.proxy.registry.GatewayRegistry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Per-replica hub of the events feature. It starts one broker consumer per exposition when the first local
 * subscription arrives, buffers the messages per channel, hands them to subscribers on {@code resources/read}
 * and stops the consumer once the last subscription is gone (after a grace delay).
 * Nothing here is replicated: each proxy replica consumes with its own consumer group.
 * @author laurent
 */
@ApplicationScoped
public class EventHub {

   private static final Logger logger = Logger.getLogger(EventHub.class);

   public static final String MESSAGE_ID_HEADER = "reshapr-message-id";

   private static final int MAX_STREAMS_PER_EXPOSITION = 200;

   private final Map<String, ExpositionState> states = new ConcurrentHashMap<>();
   private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
         Thread.ofVirtual().name("reshapr-events-hub").factory());

   private final GatewayRegistry registry;
   private final EventBrokerRegistry brokers;
   private final ChannelCatalog catalog;
   private String replicaId;

   @ConfigProperty(name = "reshapr.gateway.id")
   String gatewayId;

   @ConfigProperty(name = "reshapr.gateway.events.buffer.size", defaultValue = "1000")
   int bufferSize;

   @ConfigProperty(name = "reshapr.gateway.events.buffer.ttl", defaultValue = "300000")
   long bufferTtlMillis;

   @ConfigProperty(name = "reshapr.gateway.events.consumer.grace", defaultValue = "30000")
   long graceMillis;

   @ConfigProperty(name = "reshapr.gateway.events.replica-id")
   Optional<String> configuredReplicaId;

   /**
    * Build an event hub for a replica of the gateway. The hub is a singleton per replica, and the registry and broker
    * registry are singletons per gateway.
    * @param registry The exposition registry, to detect exposition changes and removals.
    * @param brokers The broker registry, to acquire and release brokers for expositions.
    * @param catalog The channel catalog, to get the channels of expositions.
    */
   public EventHub(GatewayRegistry registry, EventBrokerRegistry brokers, ChannelCatalog catalog) {
      this.registry = registry;
      this.brokers = brokers;
      this.catalog = catalog;
   }

   @PostConstruct
   void init() {
      this.replicaId = configuredReplicaId.orElseGet(() -> gatewayId + "-" + UUID.randomUUID().toString().substring(0, 8));
   }

   /** Name of the consumer of an exposition on this replica, unique per replica and exposition: each replica gets its own
    * copy of the stream (a Kafka consumer group id, an AMQP/MQTT client or queue name, etc.). */
   public String consumerName(String expositionId) {
      return replicaId + "-" + expositionId;
   }

   /**
    * Open a subscription on the given channels (all {@code send} channels if {@code channelKeys} is null).
    * Starts the exposition consumer if this is the first local subscription.
    */
   public synchronized EventSubscription subscribe(ExpositionEntry exposition, String userId, String subscriptionId,
                                                   @Nullable Set<String> channelKeys) {
      List<Channel> available = getSendChannels(exposition);
      Set<String> keys = new HashSet<>();
      for (Channel channel : available) {
         if (channelKeys == null || channelKeys.contains(channel.info().key())) {
            keys.add(channel.info().key());
         }
      }
      if (keys.isEmpty()) {
         throw new IllegalArgumentException("No event channel to subscribe to");
      }

      ExpositionState state = states.get(exposition.id());
      if (state == null) {
         state = new ExpositionState(exposition, available);
         states.put(exposition.id(), state);
      }
      state.cancelStop();

      String subscriberKey = subscriberKey(userId, subscriptionId);
      if (state.subscriptions.containsKey(subscriberKey)) {
         throw new IllegalArgumentException("A subscription with id '" + subscriptionId + "' is already open for this "
               + "identity. Use a distinct subscription id; several concurrent connections are only supported when "
               + "the caller is identified by an OAuth2 JWT.");
      }
      EventSubscription subscription = new EventSubscription(subscriberKey, exposition.id(), userId, keys);
      state.subscriptions.put(subscription.key(), subscription);
      for (String key : keys) {
         state.buffers.get(key).addSubscriber(subscription.key());
      }
      state.ensureConsumer();
      return subscription;
   }

   /**
    * Close a subscription and remove it from the exposition state. Stop the exposition consumer if this was the last
    * @param subscription The subscription to close.
    */
   public synchronized void unsubscribe(EventSubscription subscription) {
      subscription.close();
      ExpositionState state = states.get(subscription.expositionId());
      if (state == null) {
         return;
      }
      state.subscriptions.remove(subscription.key());
      for (String key : subscription.channelKeys()) {
         ChannelBuffer buffer = state.buffers.get(key);
         if (buffer != null) {
            buffer.removeSubscriber(subscription.key());
         }
      }
      if (state.subscriptions.isEmpty() && state.streams.isEmpty()) {
         state.scheduleStop();
      }
   }

   /**
    * Cancel the stream or listen subscription opened by a request ({@code notifications/cancelled}).
    * @return true when something was cancelled; unknown or already finished requests are ignored.
    */
   public synchronized boolean cancel(String expositionId, String userId, String requestId) {
      ExpositionState state = states.get(expositionId);
      if (state == null) {
         return false;
      }
      String ownerKey = subscriberKey(userId, requestId);
      boolean cancelled = false;
      EventSubscription subscription = state.subscriptions.get(ownerKey);
      if (subscription != null) {
         subscription.markCancelled();
         unsubscribe(subscription);
         cancelled = true;
      }
      for (EventStream stream : new ArrayList<>(state.streams)) {
         if (stream.ownerKey().equals(ownerKey)) {
            stream.markCancelled();
            stream.close();
            cancelled = true;
         }
      }
      return cancelled;
   }

   /**
    * Resolve which subscription a {@code resources/read} belongs to, in stateless mode: the explicit
    * subscription id when the client sent it, else the user's subscription on that channel.
    * @return The subscriber key or null if the caller is not subscribed.
    */
   @Nullable
   public synchronized String resolveSubscriber(String expositionId, String channelKey, String userId,
                                                @Nullable String subscriptionId) {
      ExpositionState state = states.get(expositionId);
      if (state == null) {
         return null;
      }
      if (subscriptionId != null) {
         EventSubscription sub = state.subscriptions.get(subscriberKey(userId, subscriptionId));
         return sub != null && sub.channelKeys().contains(channelKey) ? sub.key() : null;
      }
      for (EventSubscription sub : state.subscriptions.values()) {
         if (sub.userId().equals(userId) && sub.channelKeys().contains(channelKey)) {
            return sub.key();
         }
      }
      return null;
   }

   /** Read (and advance the cursor of) the messages of a channel for a subscriber. */
   public ChannelBuffer.ReadResult read(String expositionId, String channelKey, String subscriberKey) {
      ExpositionState state = states.get(expositionId);
      ChannelBuffer buffer = state == null ? null : state.buffers.get(channelKey);
      return buffer == null ? new ChannelBuffer.ReadResult(List.of(), 0) : buffer.read(subscriberKey);
   }

   /**
    * Open an {@code events/stream} subscription on a channel; call {@link EventStream#run} to serve it and
    * {@link EventStream#close()} when done. Starts the exposition consumer if needed.
    * @throws IllegalArgumentException for an unknown channel or a malformed cursor.
    * @throws IllegalStateException when the stream limit of the exposition is reached.
    */
   public synchronized EventStream openStream(ExpositionEntry exposition, String channelKey, String userId, String requestId,
                                              @Nullable Map<?, ?> arguments, @Nullable String cursor) {
      List<Channel> channels = getSendChannels(exposition);
      ChannelInfo channel = channels.stream().map(Channel::info)
            .filter(info -> info.key().equals(channelKey)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown event '" + channelKey + "'"));
      EventCursor.decode(cursor, destinationFor(EventEndpoint.parse(effectiveEndpoint(exposition)), channel, channels.size()));
      ExpositionState state = states.get(exposition.id());
      if (state == null) {
         state = new ExpositionState(exposition, channels);
         states.put(exposition.id(), state);
      }
      if (state.streams.size() >= MAX_STREAMS_PER_EXPOSITION) {
         throw new IllegalStateException("Too many open event streams on this exposition");
      }
      state.cancelStop();
      EventEndpoint endpoint = state.endpoint;
      String destination = destinationFor(endpoint, channel, channels.size());
      ExpositionState owner = state;
      EventBroker broker = broker(exposition);
      EventStream[] holder = new EventStream[1];
      holder[0] = new EventStream(channelKey, subscriberKey(userId, requestId), destination, EventFilter.and(endpoint.filter(), EventFilter.ofArguments(arguments)),
            request -> broker.read(request), broker::position,
            () -> closeStream(owner, holder[0]));
      state.streams.add(holder[0]);
      state.ensureConsumer();
      return holder[0];
   }

   private synchronized void closeStream(ExpositionState state, EventStream stream) {
      state.streams.remove(stream);
      if (state.subscriptions.isEmpty() && state.streams.isEmpty()) {
         state.scheduleStop();
      }
   }

   /**
    * Transport-neutral acknowledgement of a publication.
    * @param messageId Unique id, also sent as the {@code reshapr-message-id} message header.
    * @param destination The channel address the message was published to.
    * @param traceId OpenTelemetry trace id of the publication, if tracing is active.
    */
   public record Receipt(String messageId, String destination, @Nullable String traceId) {
   }

   /**
    * Publish a message on the topic of a {@code receive} channel. The message carries a
    * {@code reshapr-message-id} header and the W3C {@code traceparent}/{@code tracestate} headers.
    */
   @WithSpan(kind = SpanKind.PRODUCER)
   public Receipt publish(ExpositionEntry exposition, ChannelInfo channel,
                          @Nullable String key, String payload) throws Exception {
      EventEndpoint endpoint = EventEndpoint.parse(effectiveEndpoint(exposition));
      String destination = endpoint.destination() != null ? endpoint.destination() : brokers.destination(endpoint.protocol(), channel);
      Span.current().setAttribute("messaging.destination.name", destination);
      EventBroker.EventProducer producer = brokers.producer(exposition.id(), endpoint,
            exposition.configuration().backendSecret());
      String messageId = UUID.randomUUID().toString();
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put(MESSAGE_ID_HEADER, messageId);
      W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, Map::put);
      Span span = Span.current();
      span.setAttribute("messaging.message.id", messageId);
      producer.publish(destination, key, payload, headers);
      String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
      return new Receipt(messageId, destination, traceId);
   }

   /**
    * The destination of a {@code send} channel. A destination given in the endpoint overrides the channel destination,
    * which only makes sense when the exposition has a single such channel.
    */
   String destinationFor(EventEndpoint endpoint, ChannelInfo channel, int sendChannelCount) {
      return endpoint.destination() != null && sendChannelCount == 1
            ? endpoint.destination() : brokers.destination(endpoint.protocol(), channel);
   }

   /**
    * Outcome of a stateless poll of an event type.
    * @param events Matching events, oldest first.
    * @param cursor Opaque position to poll from next.
    */
   public record PollResult(List<PolledEvent> events, String cursor, boolean truncated, boolean hasMore) {
   }

   /** An occurrence returned by a poll. */
   public record PolledEvent(String eventId, long timestamp, String payload) {
   }

   /**
    * Poll the channel of an event type without any server-side state: the cursor holds the broker positions,
    * so any replica answers any poll. The endpoint filter AND the subscription arguments select the events.
    * @param cursor Opaque cursor from a previous poll, or null for "from now".
    * @param maxAgeMs Optional replay bound, ignored with a null cursor.
    * @throws IllegalArgumentException for a malformed cursor or an unknown channel.
    */
   public PollResult poll(ExpositionEntry exposition, String channelKey, @Nullable Map<?, ?> arguments,
                          @Nullable String cursor, @Nullable Long maxAgeMs, int maxEvents) {
      List<Channel> channels = getSendChannels(exposition);
      ChannelInfo channel = channels.stream().map(Channel::info)
            .filter(info -> info.key().equals(channelKey)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown event '" + channelKey + "'"));
      EventEndpoint endpoint = EventEndpoint.parse(effectiveEndpoint(exposition));
      String destination = destinationFor(endpoint, channel, channels.size());

      EventBroker broker = broker(exposition);
      String token = EventCursor.decode(cursor, destination);
      EventPosition from = token == null ? null : broker.position(token);
      Long minTimestamp = from != null && maxAgeMs != null && maxAgeMs >= 0
            ? System.currentTimeMillis() - maxAgeMs : null;
      EventBroker.ReadResult result = broker.read(
            new EventBroker.ReadRequest(destination, from, minTimestamp, maxEvents,
                  EventFilter.and(endpoint.filter(), EventFilter.ofArguments(arguments))));

      List<PolledEvent> events = new ArrayList<>();
      for (EventBroker.ConsumedRecord consumedRecord : result.records()) {
         events.add(new PolledEvent(consumedRecord.id(),
               consumedRecord.message().timestamp(), consumedRecord.message().payload()));
      }
      return new PollResult(events, EventCursor.encode(destination, result.next().encode()), result.truncated(), result.hasMore());
   }

   /** The channels on which the application emits events. */
   private List<Channel> getSendChannels(ExpositionEntry exposition) {
      return catalog.getChannels(exposition).stream().filter(channel -> !channel.receive()).toList();
   }
   
   /** The broker of the exposition, resolved from its endpoint and created lazily on first use. */
   private EventBroker broker(ExpositionEntry exposition) {
      return brokers.acquire(exposition.id(), EventEndpoint.parse(effectiveEndpoint(exposition)),
            exposition.configuration().backendSecret());
   }

   /** What the broker technology of the exposition supports, known without connecting. */
   public BrokerCapabilities capabilities(ExpositionEntry exposition) {
      try {
         return brokers.capabilities(EventEndpoint.parse(effectiveEndpoint(exposition)).protocol());
      } catch (IllegalArgumentException _) {
         return BrokerCapabilities.NONE;
      }
   }

   /** Backend endpoint of the configuration plan; {@link EventEndpoint#parse} rejects a missing one. */
   public String effectiveEndpoint(ExpositionEntry exposition) {
      return exposition.configuration().backendEndpoint();
   }

   /** Close subscriptions and consumers of expositions that were removed or updated. */
   @Scheduled(every = "5s", delayed = "5s")
   void sweep() {
      for (ExpositionState state : new ArrayList<>(states.values())) {
         ExpositionEntry current = registry.getExpositionById(state.exposition.id());
         if (current == state.exposition || state.exposition.equals(current)) {
            continue;
         }
         synchronized (this) {
            logger.infof("Exposition %s changed or removed, closing its event subscriptions", state.exposition.id());
            states.remove(state.exposition.id(), state);
            state.subscriptions.values().forEach(EventSubscription::close);
            state.streams.forEach(EventStream::closeFromHub);
            state.cancelStop();
            state.stopConsumer();
            brokers.release(state.exposition.id());
         }
      }
   }

   @PreDestroy
   void shutdown() {
      states.values().forEach(ExpositionState::stopConsumer);
      scheduler.shutdownNow();
   }

   static String subscriberKey(String userId, String subscriptionId) {
      return userId + "#" + subscriptionId;
   }

   /** State of an exposition in the hub: its consumer, buffers, subscriptions and streams. */
   private final class ExpositionState {
      final ExpositionEntry exposition;
      final Map<String, ChannelBuffer> buffers = new HashMap<>();
      final Map<String, String> channelKeyByDestination = new HashMap<>();
      final Map<String, EventSubscription> subscriptions = new ConcurrentHashMap<>();
      final Set<EventStream> streams = ConcurrentHashMap.newKeySet();
      final EventEndpoint endpoint;
      EventBroker.EventConsumer consumer;
      ScheduledFuture<?> stopTask;

      ExpositionState(ExpositionEntry exposition, List<Channel> sendChannels) {
         this.exposition = exposition;
         this.endpoint = EventEndpoint.parse(effectiveEndpoint(exposition));
         for (Channel channel : sendChannels) {
            buffers.put(channel.info().key(), new ChannelBuffer(bufferSize, bufferTtlMillis));
            channelKeyByDestination.put(destinationFor(endpoint, channel.info(), sendChannels.size()), channel.info().key());
         }
      }

      void ensureConsumer() {
         if (consumer != null) {
            return;
         }
         logger.infof("Starting event consumer %s on destinations %s", consumerName(exposition.id()), channelKeyByDestination.keySet());
         consumer = broker(exposition).startConsumer(consumerName(exposition.id()), channelKeyByDestination.keySet(), this::onRecord);
      }

      void onRecord(EventBroker.ConsumedRecord consumed) {
         // Streams apply the endpoint filter themselves so that filtered records still advance their position.
         String streamChannel = channelKeyByDestination.get(consumed.destination());
         for (EventStream stream : streams) {
            if (stream.channelKey().equals(streamChannel)) {
               stream.offer(consumed);
            }
         }
         if (endpoint.filter() != null && !endpoint.filter().matches(consumed.message().payload())) {
            return;
         }
         String channelKey = channelKeyByDestination.get(consumed.destination());
         ChannelBuffer buffer = channelKey == null ? null : buffers.get(channelKey);
         if (buffer == null) {
            return;
         }
         buffer.append(consumed.message());
         for (EventSubscription subscription : subscriptions.values()) {
            if (subscription.channelKeys().contains(channelKey)) {
               subscription.notifyUpdated(channelKey);
            }
         }
      }

      void scheduleStop() {
         cancelStop();
         stopTask = scheduler.schedule(() -> {
            synchronized (EventHub.this) {
               if (subscriptions.isEmpty() && streams.isEmpty()) {
                  logger.infof("No more subscription, stopping event consumer %s", consumerName(exposition.id()));
                  stopConsumer();
                  states.remove(exposition.id(), this);
               }
            }
         }, graceMillis, TimeUnit.MILLISECONDS);
      }

      void cancelStop() {
         if (stopTask != null) {
            stopTask.cancel(false);
            stopTask = null;
         }
      }

      void stopConsumer() {
         if (consumer != null) {
            consumer.close();
            consumer = null;
         }
      }
   }
}
