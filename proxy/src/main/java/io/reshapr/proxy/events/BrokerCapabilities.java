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
 * What a broker technology can do, independently of any connection: it is known before anything is instantiated
 * or connected, so that {@code server/discover} and {@code events/list} can declare the right interactions.
 * @param replay Positions can be read back (cursors, {@code events/poll}, resumable streams).
 * @param push Messages can be consumed live.
 * @param publish Messages can be published (the emission tool).
 * @param nativeFilter The protocol can filter messages server-side (e.g. message selectors).
 * @author laurent
 */
public record BrokerCapabilities(boolean replay, boolean push, boolean publish, boolean nativeFilter) {

   public static final BrokerCapabilities NONE = new BrokerCapabilities(false, false, false, false);
}

