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

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.infinispan.commons.api.BasicCache;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Generic, cluster-wide store for tokens the gateway acquires on its own behalf to authenticate to
 * backends (as opposed to per-user elicited secrets, held by {@link UserSecretStore}).
 * <p>
 * It is intentionally protocol-agnostic: any token-acquisition strategy (OAuth2 Client Credentials
 * today, refresh-token or JWT bearer assertion tomorrow) can reuse it by choosing a distinct
 * {@code namespace}. The effective key is {@code namespace + '/' + reference}, so different
 * strategies never collide while sharing the same replicated {@code backend-token-store} cache.
 * <p>
 * Sharing across gateway replicas means a token fetched once serves the whole cluster, which limits
 * calls to the authorization server (and respects its rate limits). The per-entry lifespan should be
 * aligned on the token {@code expires_in} at write time.
 * @author laurent
 */
@ApplicationScoped
public class BackendTokenStore {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Separator between the namespace and the reference in the composite cache key. */
   private static final String KEY_SEPARATOR = "/";

   private final BasicCache<String, String> backendTokenCache;

   /**
    * Create a new BackendTokenStore backed by the replicated backend-token cache.
    * @param backendTokenCache The {@code backend-token-store} cache (produced by {@link CacheProducer}).
    */
   public BackendTokenStore(@BackendTokenCache BasicCache<String, String> backendTokenCache) {
      this.backendTokenCache = backendTokenCache;
   }

   /**
    * Retrieve a stored token.
    * @param namespace The token-strategy namespace (e.g. {@code "client_credentials"}).
    * @param reference A stable reference within the namespace (e.g. {@code organizationId + '/' + secretName}).
    * @return The stored token, or {@code null} if none is present (or it expired).
    */
   @Nullable
   public String get(String namespace, String reference) {
      String key = composeKey(namespace, reference);
      logger.tracef("Retrieving backend token for key '%s'", key);
      return backendTokenCache.get(key);
   }

   /**
    * Store a token with an explicit lifespan, meant to be aligned on the token {@code expires_in}.
    * A non-positive or {@code null} lifespan falls back to the cache default lifespan.
    * @param namespace The token-strategy namespace (e.g. {@code "client_credentials"}).
    * @param reference A stable reference within the namespace.
    * @param value The token value.
    * @param lifespan The lifespan of the entry (typically the token remaining validity), or {@code null}.
    */
   public void put(String namespace, String reference, String value, @Nullable Duration lifespan) {
      String key = composeKey(namespace, reference);
      if (lifespan == null || lifespan.isZero() || lifespan.isNegative()) {
         logger.debugf("Storing backend token for key '%s' with default lifespan", key);
         backendTokenCache.put(key, value);
         return;
      }
      logger.debugf("Storing backend token for key '%s' with lifespan %s", key, lifespan);
      backendTokenCache.put(key, value, lifespan.toMillis(), TimeUnit.MILLISECONDS);
   }

   /**
    * Remove a stored token (e.g. after the backend rejected it with a 401).
    * @param namespace The token-strategy namespace.
    * @param reference A stable reference within the namespace.
    */
   public void remove(String namespace, String reference) {
      String key = composeKey(namespace, reference);
      logger.debugf("Removing backend token for key '%s'", key);
      backendTokenCache.remove(key);
   }

   /** Build the composite cache key from the namespace and the reference. */
   private static String composeKey(String namespace, String reference) {
      return namespace + KEY_SEPARATOR + reference;
   }
}
