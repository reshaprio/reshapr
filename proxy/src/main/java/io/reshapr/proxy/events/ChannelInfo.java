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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;

/**
 * A channel bound to an operation, as a neutral model: independent of MCP and of any broker technology.
 * The protocol bindings are kept raw; only the matching {@link EventBrokerFactory} interprets them.
 * @param key Channel key in the document ({@code channels.<key>}).
 * @param address Channel address, falls back to the key.
 * @param description Operation or channel description, may be null.
 * @param messageName First message name, may be null.
 * @param payloadSchema Inlined JSON schema of the first message payload, may be null.
 * @param contentType Message content type.
 * @param bindings Channel bindings, keyed by protocol (e.g. {@code kafka}), may be null.
 * @author laurent
 */
public record ChannelInfo(String key, String address, @Nullable String description,
                          @Nullable String messageName, @Nullable ObjectNode payloadSchema,
                          String contentType, @Nullable JsonNode bindings) {
}

