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

import io.reshapr.discovery.exposition.v1.Artifact;
import io.reshapr.discovery.exposition.v1.ArtifactType;
import io.reshapr.discovery.exposition.v1.CachePolicy;
import io.reshapr.discovery.exposition.v1.Configuration;
import io.reshapr.discovery.exposition.v1.Secret;
import io.reshapr.discovery.exposition.v1.Service;
import io.reshapr.discovery.exposition.v1.OAuth2Configuration;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ValueMapping;
import org.mapstruct.ValueMappings;

/**
 * Mapper interface to map gRPC DTOs to registry entries and vice versa in the Reshapr gateway.
 * This interface uses MapStruct to generate the implementation at compile time.
 * @author laurent
 */
@Mapper(componentModel = "cdi",
      nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface Mappers {

   @Mapping(target = "operations", source = "operationsList")
   public ServiceEntry toServiceEntry(Service service);

   @Mapping(target = "excludedOperations", source = "excludedOperationsList")
   @Mapping(target = "includedOperations", source = "includedOperationsList")
   @Mapping(target = "backendTimeout", expression = "java(configuration.hasBackendTimeout() ? configuration.getBackendTimeout() : null)")
   @Mapping(target = "cachePolicy", expression = "java(configuration.hasCachePolicy() ? toCachePolicyEntry(configuration.getCachePolicy()) : null)")
   public ConfigurationEntry toConfigurationEntry(Configuration configuration);

   /** Maps the gRPC {@link CachePolicy} optional fields to a {@link ConfigurationEntry.CachePolicyEntry}. */
   default ConfigurationEntry.CachePolicyEntry toCachePolicyEntry(CachePolicy cc) {
      if (cc == null) return null;
      Long ttlMs = cc.hasTtlMs() ? cc.getTtlMs() : null;
      String cacheScope = cc.hasCacheScope() ? cc.getCacheScope() : null;
      return new ConfigurationEntry.CachePolicyEntry(ttlMs, cacheScope);
   }

   @Mapping(target = "authorizationServers", source = "authorizationServersList")
   @Mapping(target = "scopes", source = "scopesList")
   @Mapping(target = "staticAudiences", source = "staticAudiencesList")
   public OAuth2ConfigurationEntry toOAuth2ConfigurationEntry(OAuth2Configuration oauth2Configuration);

   public SecretEntry toSecret(Secret secret);

   public OAuth2ClientConfigurationEntry toThirdPartyOAuth2ConfigurationEntry(OAuth2ClientConfigurationEntry tpOAuth2ConfigurationEntry);

   public ArtifactEntry toArtifactEntry(Artifact artifact);

   @ValueMappings({
         @ValueMapping(source = "OPEN_API_SPEC", target = "OPEN_API_SPEC"),
         @ValueMapping(source = "GRAPHQL_SCHEMA", target = "GRAPHQL_SCHEMA"),
         @ValueMapping(source = "PROTOBUF_SCHEMA", target = "PROTOBUF_SCHEMA"),
         @ValueMapping(source = "PROTOBUF_DESCRIPTOR", target = "PROTOBUF_DESCRIPTOR"),
         @ValueMapping(source = "JSON_SCHEMA", target = "JSON_SCHEMA"),
         @ValueMapping(source = "JSON_FRAGMENT", target = "JSON_FRAGMENT"),
         @ValueMapping(source = "RESHAPR_PROMPTS", target = "RESHAPR_PROMPTS"),
         @ValueMapping(source = "RESHAPR_CUSTOM_TOOLS", target = "RESHAPR_CUSTOM_TOOLS"),
         @ValueMapping(source = "RESHAPR_RESOURCES", target = "RESHAPR_RESOURCES"),
         @ValueMapping(source = "RESHAPR_TOOLS_OUTPUT_FILTERS", target = "RESHAPR_TOOLS_OUTPUT_FILTERS"),
         @ValueMapping(source = "UNRECOGNIZED", target = "JSON_FRAGMENT")
   })
   public ArtifactEntryType toArtifactEntryType(ArtifactType artifactType);
}
