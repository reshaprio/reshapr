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

import java.util.List;

/**
 * Gives the {@link EventHub} the channels of an exposition, whatever the way they are described.
 * Implemented by the layer that knows the service artifacts (for example the AsyncAPI support of the MCP layer).
 * @author laurent
 */
public interface ChannelCatalog {

   /** All the channels that the configuration plan exposes, whether the application emits or receives on them. */
   List<Channel> getChannels(ExpositionEntry exposition);
}

