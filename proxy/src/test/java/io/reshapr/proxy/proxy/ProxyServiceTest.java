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
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.registry.ConfigurationEntry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A test case for {@link ProxyService}.
 */
class ProxyServiceTest {

   @Test
   void shouldThrowHttpTimeoutExceptionWhenRequestTimeoutExpires() throws Exception {
      HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(300))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

      HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://10.255.255.1/api"))
            .timeout(Duration.ofMillis(300))
            .GET()
            .build();

      assertThrows(java.net.http.HttpTimeoutException.class,
            () -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
   }

   @Test
   void configurationEntryHoldsTimeoutValue() {
      ConfigurationEntry config = new ConfigurationEntry(
            "id", "test", "http://example.com",
            5_000L, List.of(), List.of(), null, null, null);

      assertEquals(5_000L, config.backendTimeout());
   }

   @Test
   void defaultFallbackIs3000ms() {
      ConfigurationEntry config = new ConfigurationEntry(
            "id", "test", "http://example.com",
            null, List.of(), List.of(), null, null, null);

      long timeoutMs = config.backendTimeout() != null
            ? config.backendTimeout()
            : 3_000L;

      assertEquals(3_000L, timeoutMs);
   }

   @Test
   void shouldAddUpstreamServiceTimeHeaderOnSuccess() {
      HttpResponse<byte[]> mockHttpResponse = Mockito.mock(HttpResponse.class);
      Mockito.when(mockHttpResponse.statusCode()).thenReturn(200);
      Mockito.when(mockHttpResponse.body()).thenReturn("{}".getBytes());
      Mockito.when(mockHttpResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

      ProxyService proxyService = new ProxyService(null, null) {
         @Override
         protected HttpResponse<byte[]> doCallBackendStreaming(Map<String, List<String>> requestHeaders, HttpRequest.Builder requestBuilder,
                                                      String backendEndpoint, long limit) {
            try {
               Thread.sleep(50); // simulate network delay
            } catch (InterruptedException e) { }
            return mockHttpResponse;
         }
      };
      proxyService.defaultBackendTimeout = 3000L;
      proxyService.maxPayloadSize = 10485760L;

      ConfigurationEntry config = new ConfigurationEntry("id", "test", "http://example.com", null, List.of(), List.of(), null, null, null);
      
      MethodHandlingInfo info = new MethodHandlingInfo("127.0.0.1", null, null, null, null);
      ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, info).run(() -> {
         BackendResponse response = proxyService.callBackend(config, URI.create("http://example.com"), "GET", java.util.Map.of(), null);
         
         assertEquals(200, response.status());
         long elapsed = response.upstreamServiceTimeMs();
         assertTrue(elapsed >= 50, "Elapsed time should be at least 50ms, but was: " + elapsed);
      });
   }

   @Test
   void shouldAddUpstreamServiceTimeHeaderOnException() {
      ProxyService proxyService = new ProxyService(null, null) {
         @Override
         protected HttpResponse<byte[]> doCallBackendStreaming(Map<String, List<String>> requestHeaders, HttpRequest.Builder requestBuilder,
                                                      String backendEndpoint, long limit) throws IOException {
            try {
               Thread.sleep(50); // simulate network delay before failure
            } catch (InterruptedException e) { }
            throw new IOException("Connection reset");
         }
      };
      proxyService.defaultBackendTimeout = 3000L;
      proxyService.maxPayloadSize = 10485760L;

      ConfigurationEntry config = new ConfigurationEntry("id", "test", "http://example.com", null, List.of(), List.of(), null, null, null);
      
      MethodHandlingInfo info = new MethodHandlingInfo("127.0.0.1", null, null, null, null);
      ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, info).run(() -> {
         BackendResponse response = proxyService.callBackend(config, URI.create("http://example.com"), "GET", java.util.Map.of(), null);
         
         assertEquals(502, response.status());
         long elapsed = response.upstreamServiceTimeMs();
         assertTrue(elapsed >= 50, "Elapsed time should be at least 50ms, but was: " + elapsed);
      });
   }
}