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
package io.reshapr.proxy.proxy;

import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.ClientCredentialsTokenProvider;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A service to proxy HTTP requests to external backends.
 * It handles the request, forwards it to the specified external URL, and returns the response.
 * @author laurent
 */
@ApplicationScoped
public class ProxyService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Number of client shards: half the cores, clamped to [2..8]. */
   private static final int SHARD_COUNT = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8);

   /** The sharded clients, each with its own selector thread, connection pool and VT executor. */
   private static final HttpClient[] CLIENTS = buildClients();

   /** Round-robin cursor for shard selection. */
   private static final AtomicInteger CURSOR = new AtomicInteger();

   private final SecretReferenceResolver secretResolver;
   private final UserSecretStore userSecretStore;
   private final ClientCredentialsTokenProvider clientCredentialsTokenProvider;

   @ConfigProperty(name = "reshapr.gateway.backend.http.default-timeout")
   Long defaultBackendTimeout;

   @ConfigProperty(name = "reshapr.gateway.backend.http.max-payload-size", defaultValue = "10485760")
   Long maxPayloadSize;

   /**
    * Build a ProxyService with required dependencies.
    * @param secretResolver The resolver used to resolve secret references locally on the gateway.
    * @param userSecretStore The per-user elicited secret store (stateless mode).
    * @param clientCredentialsTokenProvider Provides and caches OAuth2 client-credentials access tokens.
    */
   @Inject
   public ProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore,
                       ClientCredentialsTokenProvider clientCredentialsTokenProvider) {
      this.secretResolver = secretResolver;
      this.userSecretStore = userSecretStore;
      this.clientCredentialsTokenProvider = clientCredentialsTokenProvider;
   }

   /** Convenience constructor without a client-credentials token provider (used by tests/benchmarks). */
   public ProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
      this(secretResolver, userSecretStore, null);
   }

   private static HttpClient[] buildClients() {
      HttpClient[] clients = new HttpClient[SHARD_COUNT];
      for (int i = 0; i < SHARD_COUNT; i++) {
         clients[i] = HttpClient.newBuilder()
               .connectTimeout(Duration.ofSeconds(3))
               .version(HttpClient.Version.HTTP_1_1)
               .executor(Executors.newVirtualThreadPerTaskExecutor())
               .build();
      }
      return clients;
   }

   /**
    * @param configuration The configuration entry containing backend security details.
    * @param externalUrl The backend URL overriding the one from configuration.
    * @param method The HTTP method to use for the request (e.g., GET, POST).
    * @param headers The headers to include in the request.
    * @param body The body of the request, if applicable (e.g., for POST requests).
    * @return A BackendResponse containing the status code, body, and headers from the backend response.
    */
   public BackendResponse callBackend(ConfigurationEntry configuration, URI externalUrl, String method, Map<String, List<String>> headers, String body) {
      // Set timeout with priority to configuration value, then default if not set.
      long timeoutMs = configuration.backendTimeout() != null ? configuration.backendTimeout() : defaultBackendTimeout;

      // Treat a null or empty body as "no body": some CDNs/WAFs (e.g. Cloudflare) reject requests
      // carrying an empty payload on methods that aren't expected to have one (typically GET).
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(externalUrl)
            .timeout(Duration.ofMillis(timeoutMs))
            .method(method, body == null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

      // Apply the configuration plan header propagation policy: strip the non-overridable baseline
      // (hop-by-hop + internal), enforce the allow/deny lists (with Authorization/Cookie denied by
      // default) and apply any rewrite directives on the client-supplied headers.
      Map<String, List<String>> requestHeaders = HeaderPolicyEngine.applyRequestPolicy(headers, configuration.headerPolicy());

      // Manage the Forwarded and X-Forwarded-For headers.
      HeadersUtil.addForwardingHeaders(requestHeaders);

      // If the configuration has a backend secret, manage security headers.
      if (configuration.backendSecret() != null) {
         manageSecurityHeaders(configuration.backendSecret(), requestHeaders);
      }

      if (logger.isDebugEnabled()) {
         logger.debugf("Proxy request url: '%s'", externalUrl);
         logger.debugf("Proxy request headers: '%s'", requestHeaders);
         logger.tracef("Proxy request body: '%s'", body);
      }

      // Start counter and do the call.
      long startMs = System.currentTimeMillis();
      try {
         // Call the backend using our custom limiting BodyHandler to prevent OutOfMemoryErrors on massive payloads without performance regression.
         HttpResponse<byte[]> responseStream = doCallBackendStreaming(requestHeaders, requestBuilder, externalUrl.toString(), maxPayloadSize);

         byte[] responseBody = responseStream.body();

         if (logger.isDebugEnabled()) {
            logger.debugf("Proxy returned: '%s'", responseStream.statusCode());
            logger.debugf("Proxy response headers: '%s'", responseStream.headers());
         }

         // If authorization failed, it can be because of a bad elicitation secret value. We need to evict it.
         if (responseStream.statusCode() == 401 && configuration.backendSecret() != null && configuration.backendSecret().useElicitation()) {
            logger.warnf("Proxy authorization failed with 401, evicting elicitation secret '%s'", configuration.backendSecret().name());
            evictElicitedSecret(configuration.backendSecret());
         }

         // If authorization failed with empty body, explanations may be in the WWW-Authenticate header.
         if (responseStream.statusCode() == 401 && responseBody.length == 0 && responseStream.headers().firstValue("www-authenticate").isPresent()) {
            return buildBackendResponse(responseStream.statusCode(),
                  responseStream.headers().allValues("www-authenticate").toString().getBytes(StandardCharsets.UTF_8),
                  responseStream.headers().map(), startMs);
         }

         // Return the response as is.
         return buildBackendResponse(responseStream.statusCode(), responseBody, responseStream.headers().map(), startMs);
      } catch (HttpTimeoutException e) {
         logger.errorf("Proxy timed out after %dms calling: '%s'", timeoutMs, externalUrl);
         return buildBackendResponse(504, ("Backend timed out after " + timeoutMs + "ms").getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      } catch (ConnectException e) {
         logger.errorf("Proxy connection refused by backend '%s': %s", externalUrl, e.getMessage());
         return buildBackendResponse(503, "Service Unavailable: backend refused the connection".getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      } catch (IOException e) {
         logger.errorf("Proxy I/O error calling backend '%s': %s", externalUrl, e.getMessage());
         return buildBackendResponse(502, "Bad Gateway: unexpected network error".getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         logger.errorf("Proxy call to backend '%s' was interrupted", externalUrl);
         return buildBackendResponse(500, "Internal Server Error: request was interrupted".getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      } catch (PayloadTooLargeException e) {
         logger.errorf("Proxy rejected response from backend '%s': %s", externalUrl, e.getMessage());
         return buildBackendResponse(413, e.getMessage().getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      } catch (Exception e) {
         String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
         logger.errorf("Proxy raised unexpected error calling backend '%s': %s", externalUrl, message);
         return buildBackendResponse(500, ("Internal Server Error: " + message).getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      }
   }

   @WithSpan(kind = SpanKind.CLIENT)
   protected HttpResponse<byte[]> doCallBackendStreaming(Map<String, List<String>> requestHeaders, HttpRequest.Builder requestBuilder,
                                                @SpanAttribute("backendEndpoint") String backendEndpoint, long limit) throws IOException, InterruptedException {

      // Inject OpenTelemetry tracing headers here to get correct parent (this current client span).
      HeadersUtil.injectTracingHeaders(requestHeaders);

      // Apply headers to request builder before calling backend.
      requestHeaders.forEach((key, values) -> values.forEach(value -> requestBuilder.header(key, value)));

      // Round-robin shard selection: spreads I/O event processing over SHARD_COUNT selector threads.
      HttpClient client = CLIENTS[Math.floorMod(CURSOR.getAndIncrement(), SHARD_COUNT)];
      return client.send(requestBuilder.build(), limitingBodyHandler(limit));
   }

   /**
    * Returns a BodyHandler that enforces a strict maximum size limit to prevent OOM errors,
    * with zero buffering overhead.
    */
   private HttpResponse.BodyHandler<byte[]> limitingBodyHandler(long limit) {
      return responseInfo -> {
         responseInfo.headers().firstValueAsLong("Content-Length").ifPresent(len -> {
            if (len > limit) {
               throw new PayloadTooLargeException("Payload Too Large: response Content-Length (" + len + " bytes) exceeds maximum allowed size of " + limit + " bytes");
            }
         });
         return new LimitingBodySubscriber(HttpResponse.BodySubscribers.ofByteArray(), limit);
      };
   }

   private static class LimitingBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
      private final HttpResponse.BodySubscriber<byte[]> delegate;
      private final long limit;
      private long total = 0;
      private java.util.concurrent.Flow.Subscription subscription;

      public LimitingBodySubscriber(HttpResponse.BodySubscriber<byte[]> delegate, long limit) {
         this.delegate = delegate;
         this.limit = limit;
      }

      @Override
      public java.util.concurrent.CompletionStage<byte[]> getBody() {
         return delegate.getBody();
      }

      @Override
      public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
         this.subscription = subscription;
         delegate.onSubscribe(subscription);
      }

      @Override
      public void onNext(List<java.nio.ByteBuffer> item) {
         long chunk = 0;
         for (java.nio.ByteBuffer b : item) {
            chunk += b.remaining();
         }
         total += chunk;
         if (total > limit) {
            if (subscription != null) {
               subscription.cancel();
            }
            throw new PayloadTooLargeException("Payload Too Large: response exceeds maximum allowed size of " + limit + " bytes");
         }
         delegate.onNext(item);
      }

      @Override
      public void onError(Throwable throwable) {
         delegate.onError(throwable);
      }

      @Override
      public void onComplete() {
         delegate.onComplete();
      }
   }

   public static class PayloadTooLargeException extends RuntimeException {
      public PayloadTooLargeException(String message) {
         super(message);
      }
   }

   private BackendResponse buildBackendResponse(int status, byte[] content, Map<String, List<String>> headers, long startMs) {
      long elapsedMs = System.currentTimeMillis() - startMs;
      return new BackendResponse(status, content, headers, elapsedMs);
   }

   private void manageSecurityHeaders(SecretEntry secret, Map<String, List<String>> headers) {
      if (clientCredentialsTokenProvider != null && ClientCredentialsTokenProvider.AUTH_METHOD.equals(secret.authMethod())) {
         // OAuth2 Client Credentials: obtain (and cache) a machine-to-machine access token on the gateway.
         String cacheKey = MethodHandlingContext.getOrganizationId() + '/' + secret.name();
         String token = clientCredentialsTokenProvider.getAccessToken(cacheKey, secret.oauth2ClientConfiguration());
         if (token != null) {
            if (secret.tokenHeader() != null && !secret.tokenHeader().isBlank()) {
               headers.put(secret.tokenHeader(), List.of(token));
            } else {
               headers.put(HttpHeaders.AUTHORIZATION, List.of("Bearer " + token));
            }
         } else {
            logger.warnf("Client credentials token not available for secret '%s'", secret.name());
         }
         return;
      }
      if (!secret.useElicitation()) {
         // Add security headers based on the secret.
         if (secret.token() != null) {
            // If Token authentication required, set request property.
            String token = secretResolver.resolve(secret.token());
            if (secret.tokenHeader() != null && !secret.tokenHeader().isBlank()) {
               logger.debug("Secret contains token and token header, adding them as request header");
               headers.put(secret.tokenHeader(), List.of(token));
            } else {
               logger.debug("Secret contains token only, assuming Authorization Bearer");
               headers.put(HttpHeaders.AUTHORIZATION, List.of("Bearer " + token));
            }
         } else if (secret.username() != null && secret.password() != null) {
            // If Basic authentication required, set request property.
            logger.debug("Secret contains username/password, assuming Authorization Basic");
            String username = secretResolver.resolve(secret.username());
            String password = secretResolver.resolve(secret.password());
            String basicAuth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8));
            headers.put(HttpHeaders.AUTHORIZATION, List.of("Basic " + encodedAuth));
         }
      } else {
         // Elicitation is used: resolve the secret value according to the mode (legacy session vs stateless user).
         String secretValue = resolveElicitedSecretValue(secret);
         if (secretValue != null) {
            if (secret.tokenHeader() != null && !secret.tokenHeader().isBlank()) {
               logger.debug("Elicited secret contains token header, adding them as request header");
               headers.put(secret.tokenHeader(), List.of(secretValue));
            } else {
               logger.debug("Elicited secret does not contain token header, assuming Authorization Bearer");
               headers.put(HttpHeaders.AUTHORIZATION, List.of("Bearer " + secretValue));
            }
         } else {
            logger.warn("Elicited secret value not found for current request");
         }
      }
   }

   /**
    * Resolve an elicited secret value according to the current request mode:
    * <ul>
    *   <li><b>legacy</b> (an MCP session is bound) ⇒ read it from the session;</li>
    *   <li><b>stateless</b> (no session) ⇒ read it from the replicated user-secret store keyed by
    *       {@code (userKey, organizationId + '/' + secret.name())}.</li>
    * </ul>
    * @return the resolved secret value, or {@code null} if none is available.
    */
   private String resolveElicitedSecretValue(SecretEntry secret) {
      SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
      if (sessionInfo != null) {
         return sessionInfo.getSecretValue(secret);
      }
      String userKey = MethodHandlingContext.getUserKey();
      if (userKey == null) {
         logger.warn("No session and no user identity available, cannot resolve elicited secret value");
         return null;
      }
      return userSecretStore.getSecret(userKey, secretRef(secret));
   }

   /** Evict the elicited secret value bound to the current request (used on a backend 401). */
   private void evictElicitedSecret(SecretEntry secret) {
      SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
      if (sessionInfo != null) {
         sessionInfo.removeSecretValue(secret);
         return;
      }
      String userKey = MethodHandlingContext.getUserKey();
      if (userKey != null) {
         userSecretStore.removeSecret(userKey, secretRef(secret));
      }
   }

   /** Build the stable per-user secret reference ({@code organizationId + '/' + secret.name()}). */
   private static String secretRef(SecretEntry secret) {
      return MethodHandlingContext.getOrganizationId() + '/' + secret.name();
   }
}
