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

import io.reshapr.proxy.events.EventBroker;
import io.reshapr.proxy.events.EventEndpoint;
import io.reshapr.proxy.events.EventMessage;
import io.reshapr.proxy.events.EventPosition;
import io.reshapr.proxy.registry.SecretEntry;

import jakarta.annotation.Nullable;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Kafka implementation of {@link EventBroker}, bound to one cluster connection.
 * Consumers never auto-commit and start from the latest offset: events are live notifications, not a log replay.
 * @author laurent
 */
public class KafkaEventBroker implements EventBroker {

   private static final Logger logger = Logger.getLogger(KafkaEventBroker.class);

   /** Endpoint options understood by this broker. */
   public static final String OPTION_SECURITY_PROTOCOL = "securityProtocol";
   public static final String OPTION_SASL_MECHANISM = "saslMechanism";

   private static final int MAX_SCANNED_RECORDS = 2000;
   private static final Duration READ_DEADLINE = Duration.ofSeconds(3);
   private static final int MAX_IDLE_READERS = 8;

   private final Function<Properties, org.apache.kafka.clients.consumer.Consumer<String, String>> consumerFactory;
   private final Function<Properties, org.apache.kafka.clients.producer.Producer<String, String>> producerFactory;
   private final UnaryOperator<String> secretResolver;
   private final Deque<org.apache.kafka.clients.consumer.Consumer<String, String>> idleReaders = new ConcurrentLinkedDeque<>();
   private volatile boolean closed;
   private final EventEndpoint endpoint;
   @Nullable
   private final SecretEntry secret;

   public KafkaEventBroker(EventEndpoint endpoint, @Nullable SecretEntry secret, UnaryOperator<String> secretResolver) {
      this(endpoint, secret, KafkaConsumer::new, KafkaProducer::new, secretResolver);
   }

   /** Constructor with injectable Kafka clients, for tests. */
   public KafkaEventBroker(EventEndpoint endpoint, @Nullable SecretEntry secret,
                           Function<Properties, org.apache.kafka.clients.consumer.Consumer<String, String>> consumerFactory,
                           Function<Properties, org.apache.kafka.clients.producer.Producer<String, String>> producerFactory,
                           UnaryOperator<String> secretResolver) {
      this.endpoint = endpoint;
      this.secret = secret;
      this.consumerFactory = consumerFactory;
      this.producerFactory = producerFactory;
      this.secretResolver = secretResolver;
   }

   @Override
   public EventConsumer startConsumer(String consumerName, Set<String> destinations, Consumer<ConsumedRecord> sink) {
      Properties props = commonProperties();
      props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerName);
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

      org.apache.kafka.clients.consumer.Consumer<String, String> consumer = consumerFactory.apply(props);
      consumer.subscribe(destinations);
      PollLoop loop = new PollLoop(consumer, sink, consumerName);
      Thread.ofVirtual().name("reshapr-events-" + consumerName).start(loop);
      return loop;
   }

   @Override
   public ReadResult read(ReadRequest request) {
      org.apache.kafka.clients.consumer.Consumer<String, String> consumer = borrowReader();
      boolean healthy = false;
      try {
         ReadResult result = read(consumer, request);
         healthy = true;
         return result;
      } finally {
         releaseReader(consumer, healthy);
      }
   }

   /** Reuse an idle read consumer: creating one per read opens new broker connections every time. */
   private org.apache.kafka.clients.consumer.Consumer<String, String> borrowReader() {
      org.apache.kafka.clients.consumer.Consumer<String, String> consumer = idleReaders.pollFirst();
      if (consumer != null) {
         return consumer;
      }
      Properties props = commonProperties();
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
      props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(Math.min(MAX_SCANNED_RECORDS, 500)));
      return consumerFactory.apply(props);
   }

   private void releaseReader(org.apache.kafka.clients.consumer.Consumer<String, String> consumer, boolean healthy) {
      if (healthy && !closed && idleReaders.size() < MAX_IDLE_READERS) {
         idleReaders.addFirst(consumer);
         if (!closed) {
            return;
         }
         idleReaders.remove(consumer);
      }
      consumer.close();
   }

   @Override
   public void close() {
      closed = true;
      org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
      while ((consumer = idleReaders.pollFirst()) != null) {
         consumer.close();
      }
   }

   private ReadResult read(org.apache.kafka.clients.consumer.Consumer<String, String> consumer, ReadRequest request) {
      List<TopicPartition> partitions = new ArrayList<>();
      List<PartitionInfo> infos = consumer.partitionsFor(request.destination(), Duration.ofSeconds(5));
      for (PartitionInfo info : infos == null ? List.<PartitionInfo>of() : infos) {
         partitions.add(new TopicPartition(info.topic(), info.partition()));
      }
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
      Map<Integer, Long> next = new TreeMap<>();
      if (request.from() == null) {
         ends.forEach((tp, end) -> next.put(tp.partition(), end));
         return new ReadResult(List.of(), new KafkaPosition(next), false, false);
      }
      Map<Integer, Long> from = ((KafkaPosition) request.from()).offsets();

      Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions);
      Map<TopicPartition, Long> floorByTime = new HashMap<>();
      if (request.minTimestamp() != null) {
         Map<TopicPartition, Long> query = new HashMap<>();
         partitions.forEach(tp -> query.put(tp, request.minTimestamp()));
         consumer.offsetsForTimes(query).forEach((tp, found) ->
               floorByTime.put(tp, found == null ? ends.get(tp) : found.offset()));
      }

      boolean truncated = false;
      Map<TopicPartition, Long> positions = new HashMap<>();
      for (TopicPartition tp : partitions) {
         long begin = begins.get(tp);
         long end = ends.get(tp);
         Long requested = from.get(tp.partition());
         long start = requested == null ? begin : requested;
         if (start < begin || start > end) {
            // Retention removed the position (or the topic was recreated): restart from what exists.
            truncated |= requested != null;
            start = start < begin ? begin : end;
         }
         Long floor = floorByTime.get(tp);
         if (floor != null && floor > start) {
            truncated = true;
            start = floor;
         }
         positions.put(tp, start);
      }

      consumer.assign(partitions);
      positions.forEach(consumer::seek);
      List<ConsumedRecord> records = new ArrayList<>();
      int scanned = 0;
      long deadline = System.nanoTime() + READ_DEADLINE.toNanos();
      while (records.size() < request.maxRecords() && scanned < MAX_SCANNED_RECORDS
            && System.nanoTime() < deadline && positions.entrySet().stream().anyMatch(e -> e.getValue() < ends.get(e.getKey()))) {
         ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(200));
         for (ConsumerRecord<String, String> consumerRecord : batch) {
            TopicPartition tp = new TopicPartition(consumerRecord.topic(), consumerRecord.partition());
            if (consumerRecord.offset() >= ends.get(tp) || consumerRecord.offset() < positions.get(tp)) {
               continue;
            }
            if (records.size() >= request.maxRecords()) {
               break;
            }
            positions.put(tp, consumerRecord.offset() + 1);
            scanned++;
            EventMessage message = PollLoop.toMessage(consumerRecord);
            if (request.filter() == null || request.filter().matches(message.payload())) {
               records.add(consumed(consumerRecord, message));
            }
         }
      }
      boolean hasMore = positions.entrySet().stream().anyMatch(e -> e.getValue() < ends.get(e.getKey()));
      positions.forEach((tp, position) -> next.put(tp.partition(), position));
      return new ReadResult(records, new KafkaPosition(next), hasMore, truncated);

   }

   @Override
   public EventPosition position(String token) {
      return KafkaPosition.parse(token);
   }

   /** A consumed consumerRecord with its Kafka coordinates. */
   static ConsumedRecord consumed(ConsumerRecord<String, String> consumerRecord, EventMessage message) {
      return new ConsumedRecord(consumerRecord.topic(), consumerRecord.topic() + "-" + consumerRecord.partition() + "-" + consumerRecord.offset(), message,
            new KafkaPosition.Ref(consumerRecord.partition(), consumerRecord.offset()));
   }

   @Override
   public EventProducer createProducer() {
      Properties props = commonProperties();
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      org.apache.kafka.clients.producer.Producer<String, String> producer = producerFactory.apply(props);
      return new EventProducer() {
         @Override
         public PublishResult publish(String topic, @Nullable String key, String payload,
                                      Map<String, String> headers) throws Exception {
            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(topic, key, payload);
            headers.forEach((name, value) -> producerRecord.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
            RecordMetadata metadata = producer.send(producerRecord).get();
            return new PublishResult(metadata.topic(), metadata.partition() + "-" + metadata.offset());
         }

         @Override
         public void close() {
            producer.close(Duration.ofSeconds(2));
         }
      };
   }

   Properties commonProperties() {
      Properties props = new Properties();
      props.put("bootstrap.servers", endpoint.server());
      boolean hasCredentials = secret != null && notBlank(secret.username()) && notBlank(secret.password());
      String protocol = endpoint.options().get(OPTION_SECURITY_PROTOCOL) != null ? endpoint.options().get(OPTION_SECURITY_PROTOCOL)
            : hasCredentials ? "SASL_SSL" : "PLAINTEXT";
      props.put("security.protocol", protocol);
      if (hasCredentials) {
         String mechanism = endpoint.options().getOrDefault(OPTION_SASL_MECHANISM, "PLAIN");
         String module = mechanism.startsWith("SCRAM")
               ? "org.apache.kafka.common.security.scram.ScramLoginModule"
               : "org.apache.kafka.common.security.plain.PlainLoginModule";
         props.put("sasl.mechanism", mechanism);
         props.put("sasl.jaas.config", module + " required username=\"" + escape(secretResolver.apply(secret.username()))
               + "\" password=\"" + escape(secretResolver.apply(secret.password())) + "\";");
      }
      return props;
   }

   private static boolean notBlank(String value) {
      return value != null && !value.isBlank();
   }

   private static String escape(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"");
   }

   private static class PollLoop implements Runnable, EventConsumer {

      private final org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
      private final Consumer<ConsumedRecord> sink;
      private final String groupId;
      private volatile boolean running = true;

      PollLoop(org.apache.kafka.clients.consumer.Consumer<String, String> consumer,
               Consumer<ConsumedRecord> sink, String groupId) {
         this.consumer = consumer;
         this.sink = sink;
         this.groupId = groupId;
      }

      @Override
      public void run() {
         try {
            while (running) {
               ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
               for (ConsumerRecord<String, String> consumerRecord : records) {
                  try {
                     sink.accept(consumed(consumerRecord, toMessage(consumerRecord)));
                  } catch (RuntimeException e) {
                     logger.warnf("Event sink failed for group %s: %s", groupId, e.getMessage());
                  }
               }
            }
         } catch (WakeupException _) {
            // Closing.
         } catch (RuntimeException e) {
            logger.errorf(e, "Kafka consumer of group %s stopped on error", groupId);
         } finally {
            consumer.close(Duration.ofSeconds(2));
         }
      }

      @Override
      public void close() {
         running = false;
         consumer.wakeup();
      }

      private static EventMessage toMessage(ConsumerRecord<String, String> consumerRecord) {
         Map<String, String> headers = new HashMap<>();
         for (Header header : consumerRecord.headers()) {
            if (header.value() != null) {
               headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
         }
         return new EventMessage(0, consumerRecord.timestamp(), consumerRecord.key(), headers,
               consumerRecord.value() == null ? "" : consumerRecord.value());
      }
   }
}
