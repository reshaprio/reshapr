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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.ClientCredentialsTokenProvider;
import io.reshapr.proxy.secret.SecretReferenceResolver;
import io.reshapr.proxy.security.TokenCallCredentials;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.ClientCalls;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.reshapr.proxy.util.GrpcUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A service to proxy gRPC requests to external backends.
 * It handles the request, forwards it to the specified external URL, and returns the response.
 * @author laurent
 */
@ApplicationScoped
public class GrpcProxyService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /* Call Option used to pass gRPC Metadata from client invocation to header client interceptor */
   public static final String CUSTOM_CALL_OPTION_NAME = "request-metadata";
   public static final CallOptions.Key<Metadata> METADATA_CUSTOM_CALL_OPTION = CallOptions.Key
         .createWithDefault(CUSTOM_CALL_OPTION_NAME, null);

   private static final List<String> RESTRICTED_HEADERS = List.of("host", "connection", "accept",
         "content-type", "content-length", "user-agent");

   /** Default upper bound on the number of pooled channels (distinct endpoints). */
   private static final long DEFAULT_MAX_CHANNELS = 512L;
   /** Default idle time-to-live after which an unused endpoint's channel is evicted. */
   private static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(15);

   /** JSON to Protobuf parser. */
   private static final JsonFormat.Parser PARSER = JsonFormat.parser();
   /** Protobuf to JSON printer, configured to omit insignificant whitespace. */
   private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

   private final SecretReferenceResolver secretResolver;
   private final UserSecretStore userSecretStore;
   private final ClientCredentialsTokenProvider clientCredentialsTokenProvider;

   /** Per-endpoint {@code ManagedChannel} pool: bounded (LRU + idle TTL), evictions shut down gracefully. */
   private final Cache<String, ManagedChannel> channelCache;

   @ConfigProperty(name = "reshapr.gateway.backend.grpc.default-timeout")
   Long defaultBackendTimeout;

   /**
    * Build a GrpcProxyService with required dependencies.
    * @param secretResolver The resolver used to resolve secret references locally on the gateway.
    * @param userSecretStore The per-user elicited secret store (stateless mode).
    * @param clientCredentialsTokenProvider Provides and caches OAuth2 client-credentials access tokens.
    */
   @Inject
   public GrpcProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore,
                          ClientCredentialsTokenProvider clientCredentialsTokenProvider) {
      this.secretResolver = secretResolver;
      this.userSecretStore = userSecretStore;
      this.clientCredentialsTokenProvider = clientCredentialsTokenProvider;
      this.channelCache = Caffeine.newBuilder()
            .maximumSize(DEFAULT_MAX_CHANNELS)
            .expireAfterAccess(DEFAULT_IDLE_TTL)
            .<String, ManagedChannel>removalListener((key, channel, cause) -> {
               if (channel != null) {
                  logger.debugf("Evicting pooled gRPC channel for '%s' (cause %s)", key, cause);
                  gracefulShutdown(channel);
               }
            })
            .build();
   }

   /** Convenience constructor without a client-credentials token provider (used by tests/benchmarks). */
   public GrpcProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
      this(secretResolver, userSecretStore, null);
   }

   /**
    * Invalidate (and gracefully shut down) every pooled channel whose transport matches the given
    * backend endpoint. Intended to be called from the registry/EDS sync when an exposition is
    * redeployed so obsolete channels are reclaimed immediately rather than at idle-TTL expiry.
    */
   public void invalidateEndpoint(String backendEndpoint) {
      try {
         URL endpoint = URI.create(backendEndpoint).toURL();
         String base = endpoint.getProtocol() + "://" + endpoint.getHost() + ":" + endpoint.getPort();
         List<String> keys = channelCache.asMap().keySet().stream()
               .filter(k -> k.equals(base) || k.startsWith(base + "|"))
               .toList();
         channelCache.invalidateAll(keys);
      } catch (Exception e) {
         logger.warnf("Unable to invalidate pooled channel for endpoint '%s': %s", backendEndpoint, e.getMessage());
      }
   }

   /** @return the current number of pooled channels (for tests/benchmarks). */
   public long pooledChannelCount() {
      channelCache.cleanUp();
      return channelCache.estimatedSize();
   }

   /** Shut down all pooled channels. Usable from tests/benchmarks or a container destroy callback. */
   public void shutdown() {
      channelCache.asMap().values().forEach(GrpcProxyService::gracefulShutdown);
      channelCache.invalidateAll();
      channelCache.cleanUp();
   }

   /**
    * @param configuration The configuration entry containing backend security details.
    * @param md The MethodDescriptor for the gRPC method to call.
    * @param headers The headers to include in the request.
    * @param body The body of the request, if applicable (e.g., for POST requests).
    * @return A BackendResponse containing the status code, body, and headers from the backend response.
    */
   public BackendResponse callBackend(ConfigurationEntry configuration, Descriptors.MethodDescriptor md,
         Map<String, List<String>> headers, String body) throws IOException {

      URL endpoint = URI.create(configuration.backendEndpoint()).toURL();

      if (logger.isDebugEnabled()) {
         logger.debugf("Proxy request url: '%s'", endpoint);
         logger.debugf("Proxy request method: '%s'", md.getFullName());
         logger.debugf("Proxy request headers: '%s'", headers);
         logger.tracef("Proxy request body: '%s'", body);
      }

      // Reuse a cached ManagedChannel for this endpoint instead of creating one per call. The channel
      // carries only the transport; per-user credentials and headers are still applied per call below.
      ManagedChannel originChannel = getOrCreateChannel(endpoint, configuration);

      // Add a custom header interceptor which adds the request-specific headers.
      ClientInterceptor headerInterceptor = new HeaderInterceptor();
      Channel channel = ClientInterceptors.intercept(originChannel, headerInterceptor);

      // Use a builder for out type with a Json parser to merge content and build outMsg.
      DynamicMessage.Builder reqBuilder = DynamicMessage.newBuilder(md.getInputType());

      // Now produce the request message byte array.
      try {
         PARSER.merge(body, reqBuilder);
      } catch (InvalidProtocolBufferException e) {
         String message = e.getMessage() != null ? e.getMessage() : "Invalid JSON to Protobuf conversion";
         logger.errorf("Exception while parsing JSON to Protobuf: '%s'", message);
         return new BackendResponse(400, message.getBytes(StandardCharsets.UTF_8), Map.of());
      }
      byte[] requestBytes = reqBuilder.build().toByteArray();

      // Set timeout with priority to configuration value, then default if not set.
      long timeoutMs = configuration.backendTimeout() != null ? configuration.backendTimeout() : defaultBackendTimeout;
      CallOptions callOptions = CallOptions.DEFAULT.withDeadline(Deadline.after(timeoutMs, TimeUnit.MILLISECONDS));

      if (configuration.backendSecret() != null) {
         // Set the authentication token as call credentials if provided in the configuration.
         callOptions = manageSecurityHeaders(configuration.backendSecret(), callOptions, headers);
      }

      // Now we can call the gRPC service using the channel and method descriptor.
      long startMs = System.currentTimeMillis();
      byte[] responseBytes = null;
      try {
         String methodName = md.getService().getFullName() + "/" + md.getName();
         responseBytes = doCallBackend(channel, GrpcUtil.buildGenericUnaryMethodDescriptor(methodName), callOptions,
               headers, requestBytes, configuration.backendEndpoint());

         if (logger.isDebugEnabled()) {
            logger.debugf("Proxy returned: '%s'", Status.Code.OK.name());
            logger.tracef("Proxy response body: '%s'", new String(responseBytes, StandardCharsets.UTF_8));
         }

         try {
            // Validate incoming message parsing a DynamicMessage.
            DynamicMessage respMsg = DynamicMessage.parseFrom(md.getOutputType(), responseBytes);

            // Stream protobuf -> JSON straight to UTF-8 bytes: avoids materializing the full
            // intermediate String (UTF-16) and the extra getBytes() copy on large payloads.
            ByteArrayOutputStream baos = new ByteArrayOutputStream(responseBytes.length + (responseBytes.length >> 1));
            try (OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
               PRINTER.appendTo(respMsg, writer);
            }
            return buildBackendResponse(Status.Code.OK.value(), baos.toByteArray(), Map.of(), startMs);
         } catch (InvalidProtocolBufferException ipbe) {
            String message = ipbe.getMessage() != null ? ipbe.getMessage() : "Invalid Protobuf to JSON conversion";
            logger.errorf("Exception while converting Protobuf to JSON: '%s'", message);
            return buildBackendResponse(400, message.getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
         }
      } catch (StatusRuntimeException sre) {
         int httpStatus = mapGrpcStatusToHttp(sre.getStatus().getCode());
         String message = sre.getMessage() != null ? sre.getMessage() : sre.getStatus().getCode().name();
         logger.errorf("gRPC proxy error calling backend '%s' [%s -> HTTP %d]: %s",
               configuration.backendEndpoint(), sre.getStatus().getCode(), httpStatus, message);

         // If authorization failed, it can be because of a bad elicitation secret value. We need to evict it.
         if (httpStatus == 401 && configuration.backendSecret() != null && configuration.backendSecret().useElicitation()) {
            logger.warnf("Proxy authorization failed with 401, evicting elicitation secret '%s'", configuration.backendSecret().name());
            SecretEntry secret = configuration.backendSecret();
            SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
            if (sessionInfo != null) {
               sessionInfo.removeSecretValue(secret);
            } else {
               String userKey = MethodHandlingContext.getUserKey();
               if (userKey != null) {
                  userSecretStore.removeSecret(userKey, secretRef(secret));
               }
            }
         }

         return buildBackendResponse(httpStatus, message.getBytes(StandardCharsets.UTF_8), Map.of(), startMs);
      }
      // Note: the channel is intentionally NOT shut down here — it is pooled and reused across calls.
   }

   private BackendResponse buildBackendResponse(int status, byte[] content, Map<String, List<String>> headers, long startMs) {
      long elapsedMs = System.currentTimeMillis() - startMs;
      return new BackendResponse(status, content, headers, elapsedMs);
   }

   @WithSpan(kind = SpanKind.CLIENT)
   protected byte[] doCallBackend(Channel channel, MethodDescriptor<byte[], byte[]> unaryMethodDescriptor,
                                  CallOptions callOptions, Map<String, List<String>> headers, byte[] requestBytes,
                                  @SpanAttribute("backendEndpoint") String backendEndpoint) throws StatusRuntimeException {
      // Set the other headers as Metadata in the CallOptions.
      // Ensure OpenTelemetry tracing headers have the correct parent (this current client span).
      callOptions = callOptions.withOption(METADATA_CUSTOM_CALL_OPTION, convertHeadersToMetadata(headers));
      return ClientCalls.blockingUnaryCall(channel, unaryMethodDescriptor, callOptions, requestBytes);
   }

   private static int mapGrpcStatusToHttp(Status.Code code) {
      return switch (code) {
         case DEADLINE_EXCEEDED -> 504;
         case UNAVAILABLE -> 503;
         case UNAUTHENTICATED -> 401;
         case PERMISSION_DENIED -> 403;
         case NOT_FOUND -> 404;
         case INVALID_ARGUMENT -> 400;
         case UNIMPLEMENTED -> 501;
         case RESOURCE_EXHAUSTED -> 429;
         default -> 502;
      };
   }

   private CallOptions manageSecurityHeaders(SecretEntry secret, CallOptions callOptions, Map<String, List<String>> headers) {
      if (clientCredentialsTokenProvider != null && ClientCredentialsTokenProvider.AUTH_METHOD.equals(secret.authMethod())) {
         // OAuth2 Client Credentials: obtain (and cache) a machine-to-machine access token on the gateway.
         // Clean any conflicting incoming auth headers so they don't override the call credentials.
         String headerToRemove = secret.tokenHeader() != null ? secret.tokenHeader() : TokenCallCredentials.AUTHORIZATION_METADATA_KEY.name();
         headers.remove(headerToRemove);
         removeIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName());

         String cacheKey = MethodHandlingContext.getOrganizationId() + '/' + secret.name();
         String token = clientCredentialsTokenProvider.getAccessToken(cacheKey, secret.oauth2ClientConfiguration());
         if (token != null) {
            callOptions = callOptions.withCallCredentials(new TokenCallCredentials(token, secret.tokenHeader()));
         } else {
            logger.warnf("Client credentials token not available for secret '%s'", secret.name());
         }
         return callOptions;
      }
      if (!secret.useElicitation()) {
         // Add security headers based on the secret.
         // Set the authentication token as call credentials if provided in the configuration.
         if (secret.token() != null) {
            logger.debug("Secret contains token and maybe token header, adding them as call credentials");
            String token = secretResolver.resolve(secret.token());
            callOptions = callOptions.withCallCredentials(new TokenCallCredentials(token, secret.tokenHeader()));

            // Remove the token header from the request headers if it exists,
            String headerToRemove = secret.tokenHeader() != null
                  ? secret.tokenHeader()
                  : TokenCallCredentials.AUTHORIZATION_METADATA_KEY.name();
            headers.remove(headerToRemove);
         } else if (containsIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName())) {
            // If no backend secret token but incoming request authorization, treat it as call credentials.
            logger.debug("Got an Authorization header from incoming request, adding it as call credentials");
            callOptions = callOptions.withCallCredentials(
                  new TokenCallCredentials(getIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName())));

            // Remove the Authorization header from the request headers.
            removeIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName());
         }

      } else {
         SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();

         // We need to clean any existing token header from the request headers to they don't conflict with elicication one.
         // Remove the token header from the request headers if it exists,
         String headerToRemove = secret.tokenHeader() != null ? secret.tokenHeader() : TokenCallCredentials.AUTHORIZATION_METADATA_KEY.name();
         headers.remove(headerToRemove);

         // Remove the Authorization header from the request headers.
         removeIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName());

         if (sessionInfo != null) {
            // Elicitation is used, retrieve secret value from session info.
            String secretValue = sessionInfo.getSecretValue(secret);
            if (secretValue != null) {
               logger.debug("Elicited secret contains token header, adding them as call credentials");
               callOptions = callOptions.withCallCredentials(new TokenCallCredentials(secretValue, secret.tokenHeader()));
            } else {
               logger.warn("Elicited secret value not found in session info");
            }
         } else {
            // Stateless mode: resolve the secret from the replicated user-secret store.
            String userKey = MethodHandlingContext.getUserKey();
            String secretValue = userKey != null ? userSecretStore.getSecret(userKey, secretRef(secret)) : null;
            if (secretValue != null) {
               logger.debug("Elicited secret resolved for user, adding it as call credentials");
               callOptions = callOptions.withCallCredentials(new TokenCallCredentials(secretValue, secret.tokenHeader()));
            } else {
               logger.warn("Elicited secret value not found for current request (stateless mode)");
            }
         }
      }
      return callOptions;
   }

   /** Build the stable per-user secret reference ({@code organizationId + '/' + secret.name()}). */
   private static String secretRef(SecretEntry secret) {
      return MethodHandlingContext.getOrganizationId() + '/' + secret.name();
   }

   /**
    * Return the pooled {@code ManagedChannel} for this endpoint, building it lazily on first use.
    * The cache key is derived from the transport identity only (protocol, host, port and, for TLS,
    * the configured trust material reference). It deliberately excludes anything user-specific, so a
    * single channel is safely shared by all users while credentials remain per call.
    */
   private ManagedChannel getOrCreateChannel(URL endpoint, ConfigurationEntry configuration) {
      String key = channelKey(endpoint, configuration);
      ManagedChannel channel = channelCache.get(key, k -> buildChannel(endpoint, configuration));
      // Defensive guard against the narrow evict-then-use race: an evicted channel is shut down by the
      // removal listener; if we ever hand out a shut-down channel, drop it and rebuild.
      if (channel != null && channel.isShutdown()) {
         channelCache.asMap().remove(key, channel);
         channel = channelCache.get(key, k -> buildChannel(endpoint, configuration));
      }
      return channel;
   }

   /** Build the cache key for a channel from its transport identity. */
   private static String channelKey(URL endpoint, ConfigurationEntry configuration) {
      String base = endpoint.getProtocol() + "://" + endpoint.getHost() + ":" + endpoint.getPort();
      boolean tls = endpoint.getProtocol().equals("https") || endpoint.getPort() == 443;
      if (tls && configuration.backendSecret() != null && configuration.backendSecret().certPem() != null) {
         // Different trust material must never share a channel.
         return base + "|cert=" + configuration.backendSecret().certPem();
      }
      return base;
   }

   /** Build a new {@code ManagedChannel} for the given endpoint (plaintext or TLS). */
   private ManagedChannel buildChannel(URL endpoint, ConfigurationEntry configuration) {
      if (endpoint.getProtocol().equals("https") || endpoint.getPort() == 443) {
         TlsChannelCredentials.Builder tlsBuilder = TlsChannelCredentials.newBuilder();
         if (configuration.backendSecret() != null && configuration.backendSecret().certPem() != null) {
            // Install a trust manager with custom CA certificate.
            String certPem = secretResolver.resolve(configuration.backendSecret().certPem());
            try {
               tlsBuilder.trustManager(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
               throw new IllegalStateException("Unable to install custom trust manager for gRPC backend", e);
            }
         }
         // Build a Channel using the TLS Builder.
         return Grpc.newChannelBuilderForAddress(endpoint.getHost(), endpoint.getPort(), tlsBuilder.build())
               .build();
      }
      // Build a simple Channel using no creds (now default to plain text so usePlainText() is no longer necessary).
      return Grpc
            .newChannelBuilderForAddress(endpoint.getHost(), endpoint.getPort(), InsecureChannelCredentials.create())
            .build();
   }

   /** Shutdowns a gRPC channel gracefully. */
   private static void gracefulShutdown(ManagedChannel channel) {
      try {
         channel.shutdown();
         if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
         }
      } catch (InterruptedException e) {
         channel.shutdownNow();
         Thread.currentThread().interrupt();
      }
   }

   /** A client interceptor that adds custom headers to the gRPC request. */
   class HeaderInterceptor implements ClientInterceptor {

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                 CallOptions callOptions, Channel next) {
         return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
               // Extract custom headers from CallOptions
               Metadata customHeaders = callOptions.getOption(METADATA_CUSTOM_CALL_OPTION);
               if (customHeaders != null) {
                  logger.debugf("Adding headers to client request: %s", customHeaders.keys());
                  headers.merge(customHeaders);
               }
               logger.debugf("Headers: %s", headers);
               super.start(responseListener, headers);
            }
         };

      }
   }

   private static boolean containsIgnoreCase(Map<String, List<String>> headers, String key) {
      return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(key));
   }

   private static String getIgnoreCase(Map<String, List<String>> headers, String key) {
      return headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(key))
            .map(entry -> entry.getValue().getFirst())
            .findFirst()
            .orElse(null);
   }

   private static void removeIgnoreCase(Map<String, List<String>> headers, String key) {
      List<String> actualKeys = headers.keySet().stream()
            .filter(k -> k.equalsIgnoreCase(key))
            .toList();
      if (!actualKeys.isEmpty()) {
         actualKeys.forEach(headers::remove);
      }
   }

   private static Metadata convertHeadersToMetadata(Map<String, List<String>> headers) {
      Metadata metadata = new Metadata();

      // Manage the Forwarded and X-Forwarded-For headers.
      HeadersUtil.addForwardingHeaders(headers);

      // Also inject OpenTelemetry tracing headers.
      HeadersUtil.injectTracingHeaders(headers);

      // Some specific headers must not be included in the gRPC metadata.
      headers.entrySet().stream()
            .filter(entry -> !RESTRICTED_HEADERS.contains(entry.getKey().toLowerCase()))
            .forEach(entry -> {
               // If the header is "authorization", we need to use the correct key where case matters.
               String key = entry.getKey().equalsIgnoreCase("authorization") ? "Authorization" : entry.getKey();
               entry.getValue().forEach(value -> metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value));
            });

      return metadata;
   }
}
