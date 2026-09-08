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
package io.reshapr.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link OidcUtils}.
 * @author laurent
 */
class OidcUtilsTest {

   private final ObjectMapper objectMapper = new ObjectMapper();

   @Test
   void testFetchClientCredentialsTokenReturnsAccessTokenAndExpiresIn() throws Exception {

      try (CapturingTokenEndpoint endpoint = new CapturingTokenEndpoint(200,
            "{\"access_token\":\"access-123\",\"expires_in\":3600}")) {

         OidcUtils.OidcTokenResponse response = OidcUtils.fetchClientCredentialsToken(
               new OidcUtils.OidcEndpointConfig(endpoint.url(), "client id", "client secret"),
               List.of("read:items", "write:items"),
               objectMapper);

         assertEquals("access-123", response.accessToken());
         assertEquals(3600L, response.expiresInSeconds());

         Map<String, String> form = decodeForm(endpoint.lastRequestBody());
         assertEquals("client_credentials", form.get("grant_type"));
         assertEquals("client id", form.get("client_id"));
         assertEquals("client secret", form.get("client_secret"));
         assertEquals("read:items write:items", form.get("scope"));
      }
   }

   @Test
   void testFetchClientCredentialsTokenOmitsClientSecretWhenAbsent() throws Exception {

      try (CapturingTokenEndpoint endpoint = new CapturingTokenEndpoint(200,
            "{\"access_token\":\"access-123\",\"expires_in\":60}")) {

         OidcUtils.fetchClientCredentialsToken(
               new OidcUtils.OidcEndpointConfig(endpoint.url(), "client", null),
               List.of(),
               objectMapper);

         Map<String, String> form = decodeForm(endpoint.lastRequestBody());
         assertEquals("client_credentials", form.get("grant_type"));
         assertEquals("client", form.get("client_id"));
         assertFalse(form.containsKey("client_secret"));
         assertFalse(form.containsKey("scope"));
      }
   }

   @Test
   void testFetchClientCredentialsTokenThrowsWhenAccessTokenIsMissing() throws Exception {

      try (CapturingTokenEndpoint endpoint = new CapturingTokenEndpoint(200,
            "{\"expires_in\":3600}")) {

         OidcUtils.OidcEndpointConfig config =
               new OidcUtils.OidcEndpointConfig(endpoint.url(), "client", "secret");
         List<String> scopes = List.of("read");

         assertThrows(AuthenticationException.class,
               () -> OidcUtils.fetchClientCredentialsToken(config, scopes, objectMapper));
      }
   }

   @Test
   void testFetchClientCredentialsTokenThrowsOnNonOkResponse() throws Exception {

      try (CapturingTokenEndpoint endpoint = new CapturingTokenEndpoint(401,
            "{\"error\":\"invalid_client\"}")) {

         OidcUtils.OidcEndpointConfig config =
               new OidcUtils.OidcEndpointConfig(endpoint.url(), "client", "secret");
         List<String> scopes = List.of("read");

         assertThrows(AuthenticationException.class,
               () -> OidcUtils.fetchClientCredentialsToken(config, scopes, objectMapper));
      }
   }

   @Test
   void testFetchClientCredentialsTokenDefaultsExpiresInToZeroWhenAbsent() throws Exception {

      try (CapturingTokenEndpoint endpoint = new CapturingTokenEndpoint(200,
            "{\"access_token\":\"access-123\"}")) {

         OidcUtils.OidcTokenResponse response = OidcUtils.fetchClientCredentialsToken(
               new OidcUtils.OidcEndpointConfig(endpoint.url(), "client", "secret"),
               List.of("read"),
               objectMapper);

         assertEquals("access-123", response.accessToken());
         assertEquals(0L, response.expiresInSeconds());
      }
   }

   private static Map<String, String> decodeForm(String body) {
      if (body == null || body.isEmpty()) {
         return Map.of();
      }
      return Arrays.stream(body.split("&"))
            .map(part -> part.split("=", 2))
            .collect(Collectors.toMap(
                  part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                  part -> part.length > 1 ? URLDecoder.decode(part[1], StandardCharsets.UTF_8) : ""));
   }

   private static class CapturingTokenEndpoint implements AutoCloseable {
      private final HttpServer server;
      private final Map<String, String> requests = new ConcurrentHashMap<>();

      CapturingTokenEndpoint(int statusCode, String responseBody) throws IOException {
         server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
         server.createContext("/token", exchange -> handle(exchange, statusCode, responseBody));
         server.start();
      }

      String url() {
         return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
      }

      String lastRequestBody() {
         return requests.get("body");
      }

      private void handle(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
         requests.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
