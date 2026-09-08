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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.infinispan.commons.api.BasicCache;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ClientCredentialsTokenProvider}.
 * @author laurent
 */
class ClientCredentialsTokenProviderTest {

   @Test
   void testCacheMissFetchesTokenAndSubsequentCallUsesStore() throws Exception {

      try (CountingTokenEndpoint endpoint = new CountingTokenEndpoint(200,
            "{\"access_token\":\"fetched-token\",\"expires_in\":120}")) {

         ClientCredentialsTokenProvider provider = providerWithEmptyStore();
         OAuth2ClientConfigurationEntry config = new OAuth2ClientConfigurationEntry(
               "${env:CLIENT_ID}", "${env:CLIENT_SECRET}", null, endpoint.url(), List.of("api.read"));

         assertEquals("fetched-token", provider.getAccessToken("org/secret", config));
         assertEquals("fetched-token", provider.getAccessToken("org/secret", config));
         assertEquals(1, endpoint.callCount());
      }
   }

   @Test
   void testIncompleteConfigReturnsNullWithoutCallingEndpoint() throws Exception {

      try (CountingTokenEndpoint endpoint = new CountingTokenEndpoint(200,
            "{\"access_token\":\"fetched-token\",\"expires_in\":120}")) {

         ClientCredentialsTokenProvider provider = providerWithEmptyStore();

         assertNull(provider.getAccessToken("org/no-token-endpoint",
               new OAuth2ClientConfigurationEntry("client", "secret", null, null, List.of())));
         assertNull(provider.getAccessToken("org/no-client-id",
               new OAuth2ClientConfigurationEntry(null, "secret", null, endpoint.url(), List.of())));

         assertEquals(0, endpoint.callCount());
      }
   }

   @Test
   void tokenEndpointErrorReturnsNull() throws Exception {

      try (CountingTokenEndpoint endpoint = new CountingTokenEndpoint(500, "{\"error\":\"server_error\"}")) {
         ClientCredentialsTokenProvider provider = providerWithEmptyStore();
         OAuth2ClientConfigurationEntry config =
               new OAuth2ClientConfigurationEntry("client", "secret", null, endpoint.url(), List.of("api.read"));

         assertNull(provider.getAccessToken("org/error", config));
         assertEquals(1, endpoint.callCount());
      }
   }

   private static ClientCredentialsTokenProvider providerWithEmptyStore() {
      SecretReferenceResolver resolver = new SecretReferenceResolver(List.of(mapResolver("env", Map.of(
            "CLIENT_ID", "resolved-client",
            "CLIENT_SECRET", "resolved-secret"))));
      return new ClientCredentialsTokenProvider(resolver, new BackendTokenStore(inMemoryBasicCache()), new ObjectMapper());
   }

   private static SecretResolver mapResolver(String scheme, Map<String, String> values) {
      return new SecretResolver() {
         @Override
         public String scheme() {
            return scheme;
         }

         @Override
         public String resolve(String reference) {
            String value = values.get(reference);
            if (value == null) {
               throw new SecretResolutionException("Unknown reference '" + reference + "'");
            }
            return value;
         }
      };
   }

   @SuppressWarnings("unchecked")
   private static BasicCache<String, String> inMemoryBasicCache() {
      Map<String, String> store = new ConcurrentHashMap<>();
      InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
         case "get" -> store.get(args[0]);
         case "put" -> store.put((String) args[0], (String) args[1]);
         case "remove" -> store.remove(args[0]);
         case "clear" -> {
            store.clear();
            yield null;
         }
         case "size" -> store.size();
         case "isEmpty" -> store.isEmpty();
         case "containsKey" -> store.containsKey(args[0]);
         case "toString" -> store.toString();
         default -> throw new UnsupportedOperationException("Unsupported BasicCache method: " + method);
      };
      return (BasicCache<String, String>) java.lang.reflect.Proxy.newProxyInstance(
            ClientCredentialsTokenProviderTest.class.getClassLoader(),
            new Class<?>[]{BasicCache.class},
            handler);
   }

   private static class CountingTokenEndpoint implements AutoCloseable {
      private final HttpServer server;
      private final AtomicInteger calls = new AtomicInteger();

      CountingTokenEndpoint(int statusCode, String responseBody) throws IOException {
         server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
         server.createContext("/token", exchange -> handle(exchange, statusCode, responseBody));
         server.start();
      }

      String url() {
         return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
      }

      int callCount() {
         return calls.get();
      }

      private void handle(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
         calls.incrementAndGet();
         exchange.getRequestBody().readAllBytes();
         byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
         exchange.getResponseHeaders().add("Content-Type", "application/json");
         exchange.sendResponseHeaders(statusCode, bytes.length);
         try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
         }
      }

      @Override
      public void close() {
         server.stop(0);
      }
   }
}
