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
package io.reshapr.ctrl.model;

import io.reshapr.ctrl.security.CipheredAttributeConverter;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Type;

import java.util.List;

import static jakarta.persistence.FetchType.EAGER;

/**
 * An exposition configuration that defines how a service is exposed via Reshapr gateways.
 * @author laurent
 */
@Entity
@Table(name = "configuration_plans", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"service_id", "name"})
})
public class ConfigurationPlan extends TenantAwareEntity {

   @Column(nullable = false)
   public String name;
   public String description;

   @ManyToOne(fetch = EAGER)
   public Service service;

   @Column(name = "backend_endpoint", nullable = false)
   public String backendEndpoint;

   @Column(name = "backend_timeout")
   public Long backendTimeout;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "excluded_operations")
   public List<String> excludedOperations;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "included_operations")
   public List<String> includedOperations;

   // Solution belows creates a join table with a single column for operations.
//   @ElementCollection(fetch = EAGER)
//   @CollectionTable(name = "config_plans_exclusions", joinColumns = @JoinColumn(name = "id"))
//   @Column(name = "excluded_operations")
//   public List<String> excludedOperations;

   /**
    * Names of the attached artifacts (Prompts, Resources, CustomTools, OutputFilters) selected for this
    * configuration plan. Stored as names to stay consistent with {@link #includedOperations}/
    * {@link #excludedOperations} and with the public API, which references artifacts by name. An empty
    * (or null) list means all the attached artifacts of the service apply. The service main artifact is
    * never impacted by this selection. Artifact names are unique within a service (enforced by a unique
    * index), which makes this name-based selection deterministic.
    */
   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "included_artifacts")
   public List<String> includedArtifacts;

   @Column(name = "api_key")
   @Convert(converter = CipheredAttributeConverter.class)
   public String apiKey;

   @Column(name = "initial_access_token")
   @Convert(converter = CipheredAttributeConverter.class)
   public String initialAccessToken;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "oauth2_configuration")
   public OAuth2Configuration oauth2Configuration;

   @Column(name = "audit")
   public boolean audit;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "cache_policy")
   public CachePolicy cachePolicy;

   @ManyToOne(fetch = EAGER)
   @JoinColumn(name = "backend_secret_id")
   public Secret backendSecret;

   public record OAuth2Configuration(
         List<String> authorizationServers,
         String jwksUri,
         List<String> scopes,
         List<String> staticAudiences,
         boolean disableAudienceValidation
   ) {
   }

   /**
    * Caching directives passed to the MCP client as {@code ttlMs} and {@code cacheScope}
    * on list/read results (MCP protocol >= 2026-07-28).
    *
    * @param ttlMs       Time-to-live in milliseconds for client-side caching. Defaults to 30 000 ms.
    * @param cacheScope  Cache scope to advertise to the client (e.g. {@code "public"} or
    *                    {@code "private"}). Defaults to {@code "public"}.
    */
   public record CachePolicy(
         Long ttlMs,
         String cacheScope
   ) {
      /** Default TTL in milliseconds (30 seconds). */
      public static final long DEFAULT_TTL_MS = 30_000L;
      /** Default cache scope advertised to MCP clients. */
      public static final String DEFAULT_CACHE_SCOPE = "public";

      /** Returns a {@link CachePolicy} with the default values. */
      public static CachePolicy defaults() {
         return new CachePolicy(DEFAULT_TTL_MS, DEFAULT_CACHE_SCOPE);
      }

      /** Returns {@code ttlMs} if set, otherwise {@link #DEFAULT_TTL_MS}. */
      public long effectiveTtlMs() {
         return ttlMs != null ? ttlMs : DEFAULT_TTL_MS;
      }

      /** Returns {@code cacheScope} if set, otherwise {@link #DEFAULT_CACHE_SCOPE}. */
      public String effectiveCacheScope() {
         return cacheScope != null ? cacheScope : DEFAULT_CACHE_SCOPE;
      }
   }
}
