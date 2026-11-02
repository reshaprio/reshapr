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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The response of a backend call, as returned by {@link ProxyService} or {@link GrpcProxyService}.
 * @param status                The response status (HTTP status code for REST/GraphQL, gRPC status
 *                              code for gRPC).
 * @param content               The response body.
 * @param headers               The response headers as returned by the backend, unmodified.
 * @param upstreamServiceTimeMs The wall-clock time in milliseconds spent calling the upstream
 *                              backend (measured by the proxy). A negative value means the metric
 *                              was not captured (e.g. synthetic error response).
 */
public record BackendResponse(
      int status,
      byte[] content,
      Map<String, List<String>> headers,
      long upstreamServiceTimeMs
) {
   /** Backward-compatible constructor for callers that don't measure upstream time. */
   public BackendResponse(int status, byte[] content, Map<String, List<String>> headers) {
      this(status, content, headers, -1L);
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      BackendResponse that = (BackendResponse) o;
      return status == that.status
            && upstreamServiceTimeMs == that.upstreamServiceTimeMs
            && Arrays.equals(content, that.content)
            && Objects.equals(headers, that.headers);
   }

   @Override
   public int hashCode() {
      int result = Objects.hash(status, headers, upstreamServiceTimeMs);
      result = 31 * result + Arrays.hashCode(content);
      return result;
   }

   @Override
   public String toString() {
      return "BackendResponse[" +
            "status=" + status +
            ", content=" + (content == null ? "null" : Arrays.toString(content)) +
            ", headers=" + headers +
            ", upstreamServiceTimeMs=" + upstreamServiceTimeMs +
            ']';
   }
}

