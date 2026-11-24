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
package io.reshapr.proxy.events;

import jakarta.annotation.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parsed backend endpoint of an ASYNC exposition, e.g.
 * {@code kafka://broker:9092/my-topic?filter=json:$.org==foobar&securityProtocol=SASL_SSL&saslMechanism=PLAIN}.
 * @param protocol The broker protocol, i.e. the scheme (e.g. {@code kafka}).
 * @param server Broker address(es), {@code host:port[,host:port]}.
 * @param destination Optional destination (topic, queue, path) overriding the channel address.
 * @param filter Optional content filter.
 * @param options Every other query parameter, interpreted by the broker implementation
 *                (e.g. Kafka {@code securityProtocol} and {@code saslMechanism}); part of the connection identity.
 * @author laurent
 */
public record EventEndpoint(String protocol, String server, @Nullable String destination,
                            @Nullable EventFilter filter, Map<String, String> options) {

   public static EventEndpoint parse(String endpoint) {
      if (endpoint == null || endpoint.isBlank()) {
         throw new IllegalArgumentException("Missing backend endpoint for event exposition");
      }
      String query = null;
      String base = endpoint;
      int q = endpoint.indexOf('?');
      if (q >= 0) {
         base = endpoint.substring(0, q);
         query = endpoint.substring(q + 1);
      }
      int scheme = base.indexOf("://");
      if (scheme < 0) {
         throw new IllegalArgumentException("Invalid event endpoint '" + endpoint + "', expected <scheme>://host:port");
      }
      String protocol = base.substring(0, scheme).toLowerCase();
      String rest = base.substring(scheme + 3);
      String server = rest;
      String destination = null;
      int slash = rest.indexOf('/');
      if (slash >= 0) {
         server = rest.substring(0, slash);
         String path = rest.substring(slash + 1);
         if (!path.isBlank()) {
            destination = path;
         }
      }
      Map<String, String> params = new TreeMap<>();
      if (query != null) {
         for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
               params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
         }
      }
      EventFilter filter = EventFilter.parse(params.remove("filter"));
      return new EventEndpoint(protocol, server, destination, filter, Map.copyOf(params));
   }
}
