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
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.security.TokenCallCredentials;

import io.github.microcks.util.grpc.GrpcUtil;

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
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
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
         logger.debugf("Proxy request body: '%s'", body);
      }

      ManagedChannel originChannel;
      if (endpoint.getProtocol().startsWith("https://") || endpoint.getPort() == 443) {
         TlsChannelCredentials.Builder tlsBuilder = TlsChannelCredentials.newBuilder();
         if (configuration.backendSecret() != null && configuration.backendSecret().certPem() != null) {
            // Install a trust manager with custom CA certificate.
            tlsBuilder.trustManager(new ByteArrayInputStream(configuration.backendSecret().certPem().getBytes(StandardCharsets.UTF_8)));
         } else {
            // Install a trust manager that accepts everything and does not validate certificate chains.
            tlsBuilder.trustManager(new X509TrustManager() {
               public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                  return null;
               }

               public void checkClientTrusted(X509Certificate[] certs, String authType) {
                  // Accept everything.
               }

               public void checkServerTrusted(X509Certificate[] certs, String authType) {
                  // Accept everything.
               }
            });
         }
         // Build a Channel using the TLS Builder.
         originChannel = Grpc.newChannelBuilderForAddress(endpoint.getHost(), endpoint.getPort(), tlsBuilder.build())
               .build();
      } else {
         // Build a simple Channel using no creds (now default to plain text so usePlainText() is no longer necessary).
         originChannel = Grpc
               .newChannelBuilderForAddress(endpoint.getHost(), endpoint.getPort(), InsecureChannelCredentials.create())
               .build();
      }

      // Add a custom header interceptor which adds the request-specific headers.
      ClientInterceptor headerInterceptor = new HeaderInterceptor();
      Channel channel = ClientInterceptors.intercept(originChannel, headerInterceptor);

      // Use a builder for out type with a Json parser to merge content and build outMsg.
      DynamicMessage.Builder reqBuilder = DynamicMessage.newBuilder(md.getInputType());
      JsonFormat.Parser parser = JsonFormat.parser();

      // Now produce the request message byte array.
      try {
         parser.merge(body, reqBuilder);
      } catch (InvalidProtocolBufferException e) {
         logger.errorf("Exception while parsing JSON to Protobuf: '%s'", e.getMessage());
         return new BackendResponse(400, e.getMessage().getBytes(StandardCharsets.UTF_8), Map.of());
      }
      byte[] requestBytes = reqBuilder.build().toByteArray();

      // TODO: Use the configuration plan to determine the timeout.
      long timeout = 3000; // Default timeout of 3 seconds.
      CallOptions callOptions = CallOptions.DEFAULT.withDeadline(Deadline.after(timeout, TimeUnit.MILLISECONDS));

      if (configuration.backendSecret() != null) {
         // Set the authentication token as call credentials if provided in the configuration.
         callOptions = manageSecurityHeaders(configuration.backendSecret(), callOptions, headers);
      }

      // Set the other headers as Metadata in the CallOptions.
      callOptions = callOptions.withOption(METADATA_CUSTOM_CALL_OPTION, convertHeadersToMetadata(headers));

      // Now we can call the gRPC service using the channel and method descriptor.
      byte[] responseBytes = null;
      try {
         String methodName = md.getService().getFullName() + "/" + md.getName();
         responseBytes = doCallBackend(channel, GrpcUtil.buildGenericUnaryMethodDescriptor(methodName), callOptions, requestBytes,
               configuration.backendEndpoint());

         if (logger.isDebugEnabled()) {
            logger.debugf("Proxy returned: '%s'", Status.Code.OK.name());
            logger.debugf("Proxy response body: '%s'", new String(responseBytes, StandardCharsets.UTF_8));
         }

         String contentResponse;
         try {
            // Validate incoming message parsing a DynamicMessage.
            DynamicMessage respMsg = DynamicMessage.parseFrom(md.getOutputType(), responseBytes);

            // Now update response content with readable content.
            JsonFormat.Printer printer = JsonFormat.printer();
            contentResponse = printer.print(respMsg);
         } catch (InvalidProtocolBufferException ipbe) {
            logger.errorf("Exception while converting Protobuf to JSON: '%s'", ipbe.getMessage());
            return new BackendResponse(400, ipbe.getMessage().getBytes(StandardCharsets.UTF_8), Map.of());
         }

         return new BackendResponse(Status.Code.OK.value(),
               contentResponse.getBytes(StandardCharsets.UTF_8), Map.of());
      } catch (StatusRuntimeException sre) {
         logger.errorf(sre, "Proxy raised: '%s'", sre.getMessage());
         return new BackendResponse(500, sre.getMessage().getBytes(StandardCharsets.UTF_8), Map.of());
      } finally {
         // Shutdown the channel to release resources.
         originChannel.shutdown();
      }
   }

   @WithSpan(kind = SpanKind.CLIENT)
   protected byte[] doCallBackend(Channel channel, MethodDescriptor<byte[], byte[]> unaryMethodDescriptor,
                                  CallOptions callOptions, byte[] requestBytes,
                                  @SpanAttribute("backendEndpoint") String backendEndpoint) throws StatusRuntimeException {
      return ClientCalls.blockingUnaryCall(channel, unaryMethodDescriptor, callOptions, requestBytes);
   }

   private CallOptions manageSecurityHeaders(SecretEntry secret, CallOptions callOptions, Map<String, List<String>> headers) {
      if (!secret.useElicitation()) {
         // Add security headers based on the secret.
         // Set the authentication token as call credentials if provided in the configuration.
         if (secret.token() != null) {
            logger.debug("Secret contains token and maybe token header, adding them as call credentials");
            callOptions = callOptions.withCallCredentials(new TokenCallCredentials(secret.token(), secret.tokenHeader()));

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
            logger.warn("Session info is null, cannot retrieve elicited secret value");
         }
      }
      return callOptions;
   }

   /** */
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
