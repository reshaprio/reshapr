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
package io.reshapr.proxy.audit;

import jakarta.annotation.Nullable;

/**
 * Immutable record representing an authentication failure event for audit logging.
 *
 * @param reason A short code describing why authentication failed (e.g. "invalid_api_key", "expired_token").
 * @param serviceId The ID of the service that was targeted.
 * @param serviceName The name of the service that was targeted.
 * @param serviceVersion The version of the service that was targeted.
 * @param organizationId The organization owning the service.
 * @param sourceIp The remote IP address of the caller.
 * @param httpStatus The HTTP status code returned (401, 403, 400).
 * @param traceId The OTEL trace ID for correlation.
 * @author laurent
 */
public record AuthenticationFailureAuditEvent(
      String reason,
      String serviceId,
      String serviceName,
      String serviceVersion,
      String organizationId,
      @Nullable String sourceIp,
      int httpStatus,
      @Nullable String traceId
) {
   // Reason constants for consistent usage across the codebase.
   public static final String REASON_INVALID_API_KEY = "invalid_api_key";
   public static final String REASON_MISSING_BEARER = "missing_bearer_token";
   public static final String REASON_INVALID_JWKS_URI = "invalid_jwks_uri";
   public static final String REASON_MALFORMED_TOKEN = "malformed_token";
   public static final String REASON_INVALID_TOKEN = "invalid_token";
   public static final String REASON_FORBIDDEN_RESOURCE = "forbidden_resource";
   public static final String REASON_FORBIDDEN_SERVICE = "forbidden_service";
   public static final String REASON_FORBIDDEN_AUDIENCE = "forbidden_audience";
   public static final String REASON_MISSING_SCOPE = "missing_scope";
}

