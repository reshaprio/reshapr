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

import jakarta.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * One stream of Events after successful subscription. The broker is the source of truth: the stream tracks its
 * position and reads from it, while records pushed live by the hub consumer are only a fast
 * path (a record that directly continues the position is delivered directly; anything else, or a queue
 * overflow, or the periodic heartbeat, triggers a catch-up read). Nothing is lost whatever the live path does,
 * and a stream can resume from any cursor on any replica.
 * @author laurent
 */
public final class EventStream {

   static final int QUEUE_CAPACITY = 1000;
   private static final int BATCH = 100;
   private static final long RETRY_MILLIS = 2000;

   /** Receives what the stream produces; throws {@link IOException} when the client is gone. */
   public interface Sink {
      void active(String cursor, boolean truncated, long heartbeatMs) throws IOException;

      void event(String eventId, long timestamp, String payload, String cursor) throws IOException;

      void heartbeat(String cursor) throws IOException;

      void error(String message) throws IOException;
   }

   private final BlockingQueue<EventBroker.ConsumedRecord> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

   private final String channelKey;
   private final String ownerKey;
   private final String destination;
   private final EventFilter filter;
   private final Function<EventBroker.ReadRequest, EventBroker.ReadResult> reader;
   private final Function<String, EventPosition> positions;
   private final Runnable onClose;
   private EventPosition position;

   private volatile boolean closed;
   private volatile boolean cancelled;
   private volatile boolean overflow;

   EventStream(String channelKey, String ownerKey, String destination, @Nullable EventFilter filter,
               Function<EventBroker.ReadRequest, EventBroker.ReadResult> reader,
               Function<String, EventPosition> positions, Runnable onClose) {
      this.channelKey = channelKey;
      this.ownerKey = ownerKey;
      this.destination = destination;
      this.filter = filter;
      this.reader = reader;
      this.positions = positions;
      this.onClose = onClose;
   }

   public String channelKey() {
      return channelKey;
   }

   public String ownerKey() {
      return ownerKey;
   }

   /** True when the client cancelled the stream: nothing more must be written to it. */
   public boolean isCancelled() {
      return cancelled;
   }

   public boolean isClosed() {
      return closed;
   }

   /** Called by the hub consumer thread for each record of the channel. */
   void offer(EventBroker.ConsumedRecord record) {
      if (!closed && !queue.offer(record)) {
         overflow = true;
      }
   }

   /**
    * Run the stream until it is closed or the sink fails.
    * @param cursor Opaque cursor to resume from, or null for "from now".
    * @param maxAgeMs Optional replay bound, ignored with a null cursor.
    * @throws IOException when the sink reports the client is gone.
    */
   public void run(@Nullable String cursor, @Nullable Long maxAgeMs, long heartbeatMs, Sink sink)
         throws IOException, InterruptedException {
      String token = EventCursor.decode(cursor, destination);
      EventPosition from = token == null ? null : positions.apply(token);
      Long minTimestamp = from != null && maxAgeMs != null && maxAgeMs >= 0
            ? System.currentTimeMillis() - maxAgeMs : null;
      // Probing with maxRecords 0 yields the position the stream really starts from, and whether it moved.
      EventBroker.ReadResult probe = reader.apply(new EventBroker.ReadRequest(destination, from, minTimestamp, 0, null));
      position = probe.next();
      sink.active(cursor(), probe.truncated(), heartbeatMs);

      boolean catchUp = from != null;
      long lastBeat = System.currentTimeMillis();
      while (!closed) {
         boolean beatDue = System.currentTimeMillis() - lastBeat >= heartbeatMs;
         if (catchUp || beatDue || overflow) {
            overflow = false;
            catchUp = false;
            try {
               catchUp(heartbeatMs, sink);
            } catch (IOException e) {
               throw e;
            } catch (RuntimeException e) {
               sink.error("Unable to read events from the broker");
               catchUp = true;
               Thread.sleep(RETRY_MILLIS);
            }
         }
         if (beatDue) {
            sink.heartbeat(cursor());
            lastBeat = System.currentTimeMillis();
         }
         long wait = Math.max(1, Math.min(1000, heartbeatMs - (System.currentTimeMillis() - lastBeat)));
         EventBroker.ConsumedRecord record = queue.poll(wait, TimeUnit.MILLISECONDS);
         if (record != null && !closed) {
            catchUp |= live(record, sink);
         }
      }
   }

   private String cursor() {
      return EventCursor.encode(destination, position.encode());
   }

   /** Deliver a live record when it is the next expected one. @return true when a catch-up read is needed. */
   private boolean live(EventBroker.ConsumedRecord record, Sink sink) throws IOException {
      switch (position.check(record)) {
         case GAP:
            return true;
         case NEXT:
            position = position.advance(record);
            deliver(record, cursor(), sink);
            return false;
         default:
            return false;
      }
   }

   private void catchUp(long heartbeatMs, Sink sink) throws IOException {
      EventBroker.ReadResult result;
      do {
         result = reader.apply(new EventBroker.ReadRequest(destination, position, null, BATCH, null));
         if (result.truncated()) {
            // The position fell out of the retention window: restart from what exists and tell the client.
            position = reader.apply(new EventBroker.ReadRequest(destination, position, null, 0, null)).next();
            sink.active(cursor(), true, heartbeatMs);
            result = null;
            continue;
         }
         EventPosition running = position;
         for (EventBroker.ConsumedRecord record : result.records()) {
            running = running.advance(record);
            deliver(record, EventCursor.encode(destination, running.encode()), sink);
         }
         position = result.next();
      } while ((result == null || result.hasMore()) && !closed);
   }

   private void deliver(EventBroker.ConsumedRecord record, String cursor, Sink sink) throws IOException {
      if (filter != null && !filter.matches(record.message().payload())) {
         return;
      }
      sink.event(record.id(), record.message().timestamp(), record.message().payload(), cursor);
   }

   public void close() {
      if (!closed) {
         closed = true;
         onClose.run();
      }
   }

   /** Flag that the client abandoned the stream; the hub then closes it as usual. */
   void markCancelled() {
      cancelled = true;
   }

   /** Close without notifying the hub (it is already removing the stream). */
   void closeFromHub() {
      closed = true;
   }
}

