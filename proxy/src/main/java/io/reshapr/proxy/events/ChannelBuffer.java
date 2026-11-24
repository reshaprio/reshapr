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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory bounded buffer of the messages of one channel, with a delivery cursor per subscriber.
 * <p>
 * It is a cache, not a source of truth: messages are dropped only when the buffer is full (oldest first) or when
 * they are older than the TTL, whatever the subscribers have read. For the MCP Events extension it is a fan-out
 * cache in front of the broker (which holds the authoritative cursors); for the legacy {@code resources/read}
 * fallback it also holds a delivery cursor per subscriber, and a subscriber that missed messages is told how many.
 * The buffer is local to the proxy replica and never replicated. Delivery is at-most-once.
 * @author laurent
 */
public class ChannelBuffer {

   /**
    * Result of a read: the new messages and the number of messages lost since the previous read.
    */
   public record ReadResult(List<EventMessage> events, long lostEvents) {
   }

   private final int maxSize;
   private final long ttlMillis;
   private final Deque<EventMessage> messages = new ArrayDeque<>();
   private final Map<String, Long> cursors = new HashMap<>();
   private long lastSequence = 0;

   public ChannelBuffer(int maxSize, long ttlMillis) {
      this.maxSize = Math.max(1, maxSize);
      this.ttlMillis = ttlMillis;
   }

   /** Append a message and return it with its sequence number. */
   public synchronized EventMessage append(EventMessage message) {
      EventMessage stored = message.withSequence(++lastSequence);
      messages.addLast(stored);
      while (messages.size() > maxSize) {
         messages.removeFirst();
      }
      return stored;
   }

   /** Register a subscriber; it only sees messages arriving after this call. */
   public synchronized void addSubscriber(String subscriberKey) {
      cursors.putIfAbsent(subscriberKey, lastSequence);
   }

   public synchronized void removeSubscriber(String subscriberKey) {
      cursors.remove(subscriberKey);
   }

   public synchronized boolean hasSubscriber(String subscriberKey) {
      return cursors.containsKey(subscriberKey);
   }

   public synchronized int size() {
      return messages.size();
   }

   /** Deliver every message after the subscriber cursor and advance it. */
   public synchronized ReadResult read(String subscriberKey) {
      long now = System.currentTimeMillis();
      evict(now);
      Long cursor = cursors.get(subscriberKey);
      if (cursor == null) {
         return new ReadResult(List.of(), 0);
      }
      long firstAvailable = messages.isEmpty() ? lastSequence + 1 : messages.peekFirst().sequence();
      long lost = Math.max(0, firstAvailable - (cursor + 1));
      List<EventMessage> events = new ArrayList<>();
      for (EventMessage message : messages) {
         if (message.sequence() > cursor) {
            events.add(message);
         }
      }
      cursors.put(subscriberKey, lastSequence);
      return new ReadResult(events, lost);
   }

   private void evict(long now) {
      if (ttlMillis > 0) {
         while (!messages.isEmpty() && now - messages.peekFirst().timestamp() > ttlMillis) {
            messages.removeFirst();
         }
      }
   }
}

