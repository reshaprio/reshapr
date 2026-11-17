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
package io.reshapr.proxy.registry;

import java.util.List;

/**
 * Represents a registry exposition configuration entry.
 * @author laurent
 */
public record ConfigurationEntry(
      String id,
      String name,
      String backendEndpoint,
      Long backendTimeout,
      List<String> excludedOperations,
      List<String> includedOperations,
      String apiKey,
      OAuth2ConfigurationEntry oauth2Configuration,
      SecretEntry backendSecret,
      boolean audit,
      CachePolicyEntry cachePolicy,
      HeaderPolicyEntry headerPolicy,
      ToolExposureMode toolExposureMode) {


   public ConfigurationEntry(String id, String name, String backendEndpoint, Long backendTimeout,
                             List<String> excludedOperations, List<String> includedOperations,
                             String apiKey, OAuth2ConfigurationEntry oauth2Configuration, SecretEntry backendSecret) {
      this(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations, apiKey, oauth2Configuration, backendSecret, false, null, null);
   }

   public ConfigurationEntry(String id, String name, String backendEndpoint, Long backendTimeout,
                             List<String> excludedOperations, List<String> includedOperations,
                             String apiKey, OAuth2ConfigurationEntry oauth2Configuration, SecretEntry backendSecret, boolean audit) {
      this(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations, apiKey, oauth2Configuration, backendSecret, audit, null, null);
   }

   public ConfigurationEntry(String id, String name, String backendEndpoint, Long backendTimeout,
                             List<String> excludedOperations, List<String> includedOperations,
                             String apiKey, OAuth2ConfigurationEntry oauth2Configuration, SecretEntry backendSecret,
                             boolean audit, CachePolicyEntry cachePolicy) {
      this(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations, apiKey, oauth2Configuration, backendSecret, audit, cachePolicy, null);

   }

   public ConfigurationEntry(String id, String name, String backendEndpoint, Long backendTimeout,
                             List<String> excludedOperations, List<String> includedOperations,
                             String apiKey, OAuth2ConfigurationEntry oauth2Configuration, SecretEntry backendSecret,
                             boolean audit, CachePolicyEntry cachePolicy, HeaderPolicyEntry headerPolicy) {
      this(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations, apiKey, oauth2Configuration, backendSecret, audit, cachePolicy, headerPolicy, null);
   }

   @Override
   public String toString() {
      return "ConfigurationEntry[id=" + id + ", name= " + name + ", backendEndpoint=" + backendEndpoint
            + ", excludedOperations=" + excludedOperations + ", includedOperations=" + includedOperations
            + ", audit=" + audit + ", apiKey=" +  apiKeyString() + "]";
   }

   /**
    * Returns a copy of this configuration entry with a different backend secret. Used when fetching remote
    * content (e.g. Resources/Skills {@code remoteContent}) referencing its own {@code backendSecret}, distinct
    * from the configuration plan's backend secret.
    * @param backendSecret The secret to use instead of this entry's own {@link #backendSecret()}.
    * @return A new {@link ConfigurationEntry} identical to this one except for its backend secret.
    */
   public ConfigurationEntry withBackendSecret(SecretEntry backendSecret) {
      return new ConfigurationEntry(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations,
            apiKey, oauth2Configuration, backendSecret, audit, cachePolicy, headerPolicy, toolExposureMode);
   }

   private String apiKeyString() {
      return (apiKey != null ? "*******" : "null");
   }

   /**
    * Determine whether an operation (referenced by its service operation name) is exposed by this
    * configuration plan's include/exclude lists. Included operations take precedence over excluded
    * ones; when neither list is set, all operations are exposed.
    * @param operationName The service operation name to check.
    * @return true when the operation is exposed by the plan, false otherwise.
    */
   public boolean exposesOperation(String operationName) {
      if (includedOperations != null && !includedOperations.isEmpty()) {
         return includedOperations.contains(operationName);
      }
      if (excludedOperations != null && !excludedOperations.isEmpty()) {
         return !excludedOperations.contains(operationName);
      }
      return true;
   }

   /**
    * Caching directives forwarded from the ConfigurationPlan to MCP responses.
    * Both fields are optional; when null the proxy falls back to the defaults.
    *
    * @param ttlMs      Time-to-live in milliseconds.
    * @param cacheScope Cache scope (e.g. "public" or "private").
    */
   public record CachePolicyEntry(Long ttlMs, String cacheScope) {

      public static final long DEFAULT_TTL_MS = 30_000L;
      public static final String DEFAULT_CACHE_SCOPE = "public";

      /** Returns {@code ttlMs} if set, otherwise the default (30 000 ms). */
      public long effectiveTtlMs() {
         return ttlMs != null ? ttlMs : DEFAULT_TTL_MS;
      }

      /** Returns {@code cacheScope} if set, otherwise the default ("public"). */
      public String effectiveCacheScope() {
         return cacheScope != null ? cacheScope : DEFAULT_CACHE_SCOPE;
      }
   }

   /**
    * Header propagation policy forwarded from the ConfigurationPlan. Only the request direction
    * is honored today; the response direction is reserved.
    *
    * @param request  Rules applied to headers forwarded to the backend (may be {@code null}).
    * @param response Reserved rules for the backend → client direction (may be {@code null}).
    */
   public record HeaderPolicyEntry(HeaderRulesEntry request, HeaderRulesEntry response) {
   }

   /**
    * A set of allow/deny/rename directives applied in a single direction.
    *
    * @param allow  Allow-list; when non-empty only these headers pass (deny-by-default).
    * @param deny   Deny-list; these headers are removed.
    * @param rename Rename directives applied after filtering.
    */
   public record HeaderRulesEntry(List<String> allow, List<String> deny, List<HeaderRenameEntry> rename) {
   }

   /**
    * A single header rename directive: removes {@code from} and sets {@code to} with its values.
    *
    * @param from Source header name.
    * @param to   Target header name.
    */
   public record HeaderRenameEntry(String from, String to) {
   }

   /**
    * The tool exposure mode effectively applied by this configuration plan, defaulting to
    * {@link ToolExposureMode#DEFAULT} when the plan declares none.
    * @return The effective tool exposure mode, never {@code null}.
    */
   public ToolExposureMode effectiveToolExposureMode() {
      return toolExposureMode != null ? toolExposureMode : ToolExposureMode.DEFAULT;
   }
}
