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
package io.reshapr.proxy.mcp.state;

import io.reshapr.proxy.context.SessionInfo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.infinispan.commons.api.BasicCache;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * Exposes the three replicated MCP state caches (backed by the embedded Infinispan cache manager
 * configured in {@code infinispan.xml}) as strongly-typed CDI beans, so the stores can inject a
 * {@link BasicCache} without depending on the Infinispan extension qualifiers or cache names.
 * <p>
 * Replication, expiration and partition handling are defined declaratively in {@code infinispan.xml}.
 * Marshalling is handled by the generated Protostream serialization context initializer, auto-registered
 * by the extension.
 * @author laurent
 */
@ApplicationScoped
public class CacheProducer {

   /** Name of the replicated cache holding legacy MCP sessions ({@code SessionInfo}). */
   public static final String SESSION_CACHE = "session-store";
   /** Name of the replicated cache holding in-flight elicitations ({@code ElicitationInfo}). */
   public static final String ELICITATION_CACHE = "elicitation-store";
   /** Name of the replicated cache holding per-user elicited secrets (stateless mode). */
   public static final String USER_SECRET_CACHE = "user-secret-store";
   /** Name of the replicated cache holding tokens the gateway acquires to authenticate to backends. */
   public static final String BACKEND_TOKEN_CACHE = "backend-token-store";

   private final EmbeddedCacheManager cacheManager;

   /**
    * Build the producer with the embedded cache manager provided by the Infinispan extension.
    * @param cacheManager The managed embedded cache manager.
    */
   public CacheProducer(EmbeddedCacheManager cacheManager) {
      this.cacheManager = cacheManager;
   }

   /**
    * Produce the typed session cache (legacy mode).
    * @return The {@code session-store} cache keyed by session id.
    */
   @Produces
   @ApplicationScoped
   public BasicCache<String, SessionInfo> sessionCache() {
      return cacheManager.getCache(SESSION_CACHE);
   }

   /**
    * Produce the typed elicitation cache.
    * @return The {@code elicitation-store} cache keyed by elicitation id.
    */
   @Produces
   @ApplicationScoped
   public BasicCache<String, ElicitationInfo> elicitationCache() {
      return cacheManager.getCache(ELICITATION_CACHE);
   }

   /**
    * Produce the typed user-secret cache (stateless mode). The key is the composite
    * {@code iss+sub / secretRef} string built by the {@code UserSecretStore} (see plan Step 6).
    * @return The {@code user-secret-store} cache keyed by the composite user-secret reference.
    */
   @Produces
   @ApplicationScoped
   public BasicCache<String, String> userSecretCache() {
      return cacheManager.getCache(USER_SECRET_CACHE);
   }

   /**
    * Produce the typed backend-token cache. Keyed by the {@code namespace + '/' + reference} string
    * built by {@link BackendTokenStore}. Qualified with {@link BackendTokenCache} to disambiguate it
    * from {@link #userSecretCache()} (both are {@code BasicCache<String, String>}).
    * @return The {@code backend-token-store} cache.
    */
   @Produces
   @ApplicationScoped
   @BackendTokenCache
   public BasicCache<String, String> backendTokenCache() {
      return cacheManager.getCache(BACKEND_TOKEN_CACHE);
   }
}

