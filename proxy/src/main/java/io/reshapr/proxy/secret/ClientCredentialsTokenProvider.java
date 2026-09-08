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
package io.reshapr.proxy.secret;

import io.reshapr.proxy.mcp.state.BackendTokenStore;
import io.reshapr.proxy.registry.OAuth2ClientConfigurationEntry;
import io.reshapr.security.AuthenticationException;
import io.reshapr.security.OidcUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Provides OAuth2 access tokens obtained through the {@code client_credentials} grant (machine-to-machine
 * backend authentication). Tokens are cached in the cluster-wide {@link BackendTokenStore} with a lifespan
 * derived from the token lifetime returned by the authorization server (minus a safety margin), so the
 * token endpoint is not hit on every backend call and a token fetched by one replica serves the whole
 * cluster.
 * @author laurent
 */
@ApplicationScoped
public class ClientCredentialsTokenProvider {

   /** The {@code authMethod} discriminator handled by this provider (matches {@code SecretAuthMethod.OAUTH2_CLIENT_CREDENTIALS}). */
   public static final String AUTH_METHOD = "OAUTH2_CLIENT_CREDENTIALS";

   /** Namespace used to partition this provider's entries within the shared backend-token store. */
   private static final String TOKEN_NAMESPACE = "client_credentials";

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Renew the token this many seconds before its actual expiry to avoid using a just-expired token. */
   private static final long REFRESH_MARGIN_SECONDS = 15L;
   /** Fallback lifetime used when the authorization server does not return an {@code expires_in}. */
   private static final long DEFAULT_TTL_SECONDS = 300L;
   /** Lower bound for the cached lifetime, so a very short-lived token is not cached for a negative duration. */
   private static final long MIN_TTL_SECONDS = 5L;

   private final SecretReferenceResolver secretResolver;
   private final BackendTokenStore backendTokenStore;
   private final ObjectMapper objectMapper;

   /**
    * Build the provider.
    * @param secretResolver used to resolve {@code ${scheme:ref}} placeholders in the client id/secret locally on the gateway.
    * @param backendTokenStore the cluster-wide store caching acquired backend tokens.
    * @param objectMapper the object mapper used to parse JSON responses from the token endpoint.
    */
   public ClientCredentialsTokenProvider(SecretReferenceResolver secretResolver, BackendTokenStore backendTokenStore, ObjectMapper objectMapper) {
      this.secretResolver = secretResolver;
      this.backendTokenStore = backendTokenStore;
      this.objectMapper = objectMapper;
   }

   /**
    * Return a valid access token for the given secret, fetching (and caching) a new one if needed.
    * @param reference a stable reference identifying the secret (e.g. {@code organizationId + '/' + secretName}).
    * @param config the OAuth2 client configuration to use for the {@code client_credentials} exchange.
    * @return the access token, or {@code null} if it could not be obtained.
    */
   public String getAccessToken(String reference, OAuth2ClientConfigurationEntry config) {
      if (config == null || config.tokenEndpoint() == null || config.clientId() == null) {
         logger.warn("Cannot obtain client credentials token: incomplete OAuth2 client configuration");
         return null;
      }

      // Fast path: a still-valid token is already cached cluster-wide.
      String cached = backendTokenStore.get(TOKEN_NAMESPACE, reference);
      if (cached != null) {
         return cached;
      }

      // Cache miss (or expired): fetch a fresh token and cache it with its remaining validity.
      return fetchAndStore(reference, config);
   }

   private String fetchAndStore(String reference, OAuth2ClientConfigurationEntry config) {
      try {
         String clientId = secretResolver.resolve(config.clientId());
         String clientSecret = secretResolver.resolve(config.clientSecret());
         OidcUtils.OidcTokenResponse response = OidcUtils.fetchClientCredentialsToken(
               new OidcUtils.OidcEndpointConfig(config.tokenEndpoint(), clientId, clientSecret),
               config.scopes(), objectMapper);
         long ttlSeconds = response.expiresInSeconds() > 0
               ? Math.max(response.expiresInSeconds() - REFRESH_MARGIN_SECONDS, MIN_TTL_SECONDS)
               : DEFAULT_TTL_SECONDS;
         logger.debugf("Obtained client credentials token from '%s', cached for %ds", config.tokenEndpoint(), ttlSeconds);
         backendTokenStore.put(TOKEN_NAMESPACE, reference, response.accessToken(), Duration.ofSeconds(ttlSeconds));
         return response.accessToken();
      } catch (AuthenticationException e) {
         logger.errorf("Failed to obtain client credentials token from '%s': %s", config.tokenEndpoint(), e.getMessage());
         return null;
      }
   }
}
