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

import io.reshapr.proxy.events.Channel;
import io.reshapr.proxy.events.ChannelCatalog;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ExpositionEntry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * {@link ChannelCatalog} backed by the AsyncAPI artifact of the exposition, parsed once in the {@link WorkCache}.
 * @author laurent
 */
@ApplicationScoped
public class AsyncAPIChannelCatalog implements ChannelCatalog {

   private final WorkCache workCache;

   public AsyncAPIChannelCatalog(WorkCache workCache) {
      this.workCache = workCache;
   }

   @Override
   public List<Channel> getChannels(ExpositionEntry exposition) {
      return AsyncAPIChannels.channels(exposition, workCache);
   }
}
