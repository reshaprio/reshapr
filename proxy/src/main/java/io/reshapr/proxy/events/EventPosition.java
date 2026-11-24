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

/**
 * A position in the stream of a destination, owned by the {@link EventBroker} that produced it: the gateway
 * never looks inside (partition offsets for Kafka, a page token or a sequence for other technologies). It is
 * immutable; the opaque {@link #encode() token} travels in the MCP Events cursor.
 * @author laurent
 */
public interface EventPosition {

   /** How a live record relates to a position. */
   enum Continuity {
      /** The record is exactly the next one expected. */
      NEXT,
      /** The record was already covered by the position. */
      DUPLICATE,
      /** Records are missing between the position and this one: a catch-up read is needed. */
      GAP
   }

   /** The position right after the given consumedRecord. */
   EventPosition advance(EventBroker.ConsumedRecord consumedRecord);

   /** How a record received live relates to this position. */
   Continuity check(EventBroker.ConsumedRecord consumedRecord);

   /** Compact text form, understood by {@link EventBroker#position(String)} of the same broker. */
   String encode();
}
