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
import io.reshapr.proxy.events.EventPosition;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kafka {@link EventPosition}: the next offset to read on each partition. The token is {@code partition:offset}
 * pairs separated by commas (e.g. {@code 0:1042,1:977}).
 * @author laurent
 */
public class KafkaPosition implements EventPosition {

   private final Map<Integer, Long> offsets;

   /** Create a position from the next offset per partition. */
   public KafkaPosition(Map<Integer, Long> offsets) {
      this.offsets = Collections.unmodifiableMap(new TreeMap<>(offsets));
   }

   /** Get the next offset per partition. */
   public Map<Integer, Long> offsets() {
      return offsets;
   }

   @Override
   public EventPosition advance(EventBroker.ConsumedRecord consumedRecord) {
      if (consumedRecord.ref() instanceof Ref(int partition, long offset)) {
         Map<Integer, Long> next = new TreeMap<>(offsets);
         next.put(partition, offset + 1);
         return new KafkaPosition(next);
      }
      return this;
   }

   @Override
   public Continuity check(EventBroker.ConsumedRecord consumedRecord) {
      if (!(consumedRecord.ref() instanceof Ref(int partition, long offset))) {
         return Continuity.GAP;
      }
      Long expected = offsets.get(partition);
      if (expected == null || offset > expected) {
         return Continuity.GAP;
      }
      return offset == expected ? Continuity.NEXT : Continuity.DUPLICATE;
   }

   @Override
   public String encode() {
      StringBuilder sb = new StringBuilder();
      offsets.forEach((partition, offset) -> sb.append(sb.isEmpty() ? "" : ",").append(partition).append(':').append(offset));
      return sb.toString();
   }

   /** Parse a position from its token. */
   public static KafkaPosition parse(String token) {
      Map<Integer, Long> offsets = new TreeMap<>();
      try {
         if (!token.isEmpty()) {
            for (String pair : token.split(",")) {
               String[] parts = pair.split(":");
               if (parts.length != 2) {
                  throw new IllegalArgumentException("Invalid cursor");
               }
               offsets.put(Integer.valueOf(parts[0]), Long.valueOf(parts[1]));
            }
         }
      } catch (NumberFormatException _) {
         throw new IllegalArgumentException("Invalid cursor");
      }
      return new KafkaPosition(offsets);
   }

   /** Broker-private reference carried by a {@link EventBroker.ConsumedRecord}. */
   public record Ref(int partition, long offset) {
   }
}
