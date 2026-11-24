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
package io.reshapr.proxy.mcp.events;

import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.events.Channel;
import io.reshapr.proxy.events.ChannelInfo;
import io.reshapr.proxy.mcp.WorkCache;

import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps the operations of an ASYNC exposition to channels, from the AsyncAPI main artifact: {@code send} operations are event sources
 * (resources + subscriptions), {@code receive} operations are commands (tools).
 * @author laurent
 */
public final class AsyncAPIChannels {

   public static final String SEND = "SEND";
   public static final String RECEIVE = "RECEIVE";
   public static final String URI_PREFIX = "events://";

   private static final String CACHE_KEY = "asyncapi";

   private AsyncAPIChannels() {
   }

   /** The resource URI of an event channel. */
   public static String uri(String channelKey) {
      return URI_PREFIX + channelKey;
   }

   public static String channelKeyFromUri(String uri) {
      return uri != null && uri.startsWith(URI_PREFIX) ? uri.substring(URI_PREFIX.length()) : null;
   }

   /** The parsed AsyncAPI main artifact of an exposition, cached across calls. */
   @Nullable
   public static AsyncAPIDocument getDocument(ExpositionEntry exposition, WorkCache workCache) {
      ArtifactEntry artifact = exposition.mainArtifact();
      if (artifact == null || artifact.type() != ArtifactEntryType.ASYNC_API_SPEC) {
         return null;
      }
      return (AsyncAPIDocument) workCache.computeIfAbsent(artifact.id(), CACHE_KEY, () -> AsyncAPIDocument.parse(artifact.content()));
   }

   /** All the channels of the operations that the configuration plan exposes. */
   public static List<Channel> channels(ExpositionEntry exposition, WorkCache workCache) {
      List<Channel> result = new ArrayList<>();
      AsyncAPIDocument document = getDocument(exposition, workCache);
      if (document == null) {
         return result;
      }
      for (OperationEntry operation : exposition.service().operations()) {
         if (!exposition.configuration().exposesOperation(operation.name())) {
            continue;
         }
         ChannelInfo info = document.getChannelForOperation(operation.name());
         if (info != null) {
            result.add(new Channel(operation.name(), RECEIVE.equalsIgnoreCase(operation.method()), info));
         }
      }
      return result;
   }

   /** Channels of the operations of the given kind that the configuration plan exposes. */
   public static List<Channel> channels(ExpositionEntry exposition, WorkCache workCache, boolean receive) {
      return channels(exposition, workCache).stream().filter(channel -> channel.receive() == receive).toList();
   }
}
