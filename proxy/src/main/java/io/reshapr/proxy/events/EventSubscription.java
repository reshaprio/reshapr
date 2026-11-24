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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A live {@code subscriptions/listen} stream: the channels it watches and the resource URIs updated since
 * the last wait. Updates are coalesced per URI, so a burst of messages yields one notification.
 * @author laurent
 */
public class EventSubscription {

   private final String key;
   private final String expositionId;
   private final String userId;
   private final Set<String> channelKeys;
   private final ReentrantLock lock = new ReentrantLock();
   private final Condition signal = lock.newCondition();
   private final Set<String> pending = new LinkedHashSet<>();
   private boolean closed = false;

   EventSubscription(String key, String expositionId, String userId, Set<String> channelKeys) {
      this.key = key;
      this.expositionId = expositionId;
      this.userId = userId;
      this.channelKeys = Set.copyOf(channelKeys);
   }

   /**
    * Subscriber key: the cursor owner in each {@link ChannelBuffer}. It is not the MCP subscription id
    * (the JSON-RPC id of the {@code subscriptions/listen} request, chosen by the client and only unique per
    * client): it is derived from it as {@code userId#subscriptionId}, so it is unique per user and
    * subscription across the clients sharing an exposition.
    * @see EventHub#resolveSubscriber
    */
   public String key() {
      return key;
   }

   public String expositionId() {
      return expositionId;
   }

   public String userId() {
      return userId;
   }

   public Set<String> channelKeys() {
      return channelKeys;
   }

   void notifyUpdated(String uri) {
      lock.lock();
      try {
         pending.add(uri);
         signal.signalAll();
      } finally {
         lock.unlock();
      }
   }

   void close() {
      lock.lock();
      try {
         closed = true;
         signal.signalAll();
      } finally {
         lock.unlock();
      }
   }

   public boolean isClosed() {
      lock.lock();
      try {
         return closed;
      } finally {
         lock.unlock();
      }
   }

   /**
    * Wait for updated URIs.
    * @return The URIs updated since the previous call, empty on timeout or when closed.
    */
   public List<String> awaitUpdates(long timeout, TimeUnit unit) throws InterruptedException {
      long nanos = unit.toNanos(timeout);
      lock.lock();
      try {
         while (pending.isEmpty() && !closed && nanos > 0) {
            nanos = signal.awaitNanos(nanos);
         }
         List<String> uris = new ArrayList<>(pending);
         pending.clear();
         return uris;
      } finally {
         lock.unlock();
      }
   }
}
