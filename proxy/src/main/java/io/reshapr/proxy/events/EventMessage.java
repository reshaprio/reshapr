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

import java.util.Map;

/**
 * A message consumed from a broker, held in a {@link ChannelBuffer}.
 * @param sequence Monotonic sequence number assigned by the buffer (0 before insertion).
 * @param timestamp Record timestamp in epoch milliseconds.
 * @param key Optional record key.
 * @param headers Record headers (string values).
 * @param payload Record value as text.
 * @author laurent
 */
public record EventMessage(long sequence, long timestamp, @Nullable String key,
                           Map<String, String> headers, String payload) {

   public EventMessage withSequence(long sequence) {
      return new EventMessage(sequence, timestamp, key, headers, payload);
   }
}
