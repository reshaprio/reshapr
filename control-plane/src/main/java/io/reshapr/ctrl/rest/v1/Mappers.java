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
package io.reshapr.ctrl.rest.v1;

import io.reshapr.ctrl.model.ActiveExposition;
import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.ConfigurationTemplate;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.Gateway;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.Secret.OAuth2ClientConfiguration;
import io.reshapr.ctrl.model.SecretType;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.service.ArtifactDeletionImpact;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ValueMapping;
import org.mapstruct.ValueMappings;

import java.util.List;

/**
 * Mapper interface to map entities to DTOs and vice versa in the Reshapr control plane REST API v1.
 * This interface uses MapStruct to generate the implementation at compile time.
 * @author laurent
 */
@Mapper(componentModel = "cdi",
      nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface Mappers {

   ArtifactDTO toResource(Artifact artifact);

   ArtifactDeletionImpactDTO toResource(ArtifactDeletionImpact impact);

   ArtifactDeletionImpactDTO.ImpactedPlanDTO toResource(ArtifactDeletionImpact.ImpactedPlan plan);

   GatewayGroupDTO toResource(GatewayGroup gatewayGroup);

   GatewayGroup fromResource(GatewayGroupDTO gatewayGroupDTO);

   List<GatewayGroupDTO> toGGResources(List<GatewayGroup> gatewayGroup);

   GatewayDTO toResource(Gateway gateway);

   List<GatewayViewDTO> toGWResources(List<Gateway> gateways);

   OAuth2ClientConfigurationDTO toResource(OAuth2ClientConfiguration oauth2ClientConfiguration);

   OAuth2ClientConfiguration toResource(OAuth2ClientConfigurationDTO oauth2ClientConfigurationDTO);

   default ApiTokenDTO toResource(ApiToken apiToken) {
      // Manage mapping explicitly to avoid exposing sensitive information.
      if (apiToken == null) {
         return null;
      }
      return new ApiTokenDTO(
            apiToken.id,
            apiToken.organizationId,
            apiToken.name,
            apiToken.validUntil,
            apiToken.user != null ? apiToken.user.username : null
      );
   }

   List<ApiTokenDTO> toATResources(List<ApiToken> apiTokens);

   default SecretDTO toResource(Secret secret) {
      // Manage mapping explicitly to avoid exposing sensitive information
      // Was previously using @BeforeMapping, but it doesn't work as the Secret entity
      // is cached at the Hibernate level and thus next reads see the '*******' values.
      if (secret == null) {
         return null;
      }
      String id = secret.id;
      String organizationId = secret.organizationId;
      String name = secret.name;
      String description = secret.description;
      SecretType type = secret.type;
      String username = secret.username;

      String tokenHeader = secret.tokenHeader;
      String certPem = secret.certPem;
      String password = secret.getPassword() != null ? "*******" : null;
      String token = secret.getToken() != null ? "*******" : null;

      return new SecretDTO(id, organizationId, name, description, type, username, password, token, tokenHeader, certPem,
            secret.useElicitation, toResource(secret.oauth2ClientConfiguration));
   }

   Secret fromResource(SecretDTO secretDTO);

   @ValueMappings({
         @ValueMapping(source = "ARTIFACT", target = "ARTIFACT"),
         @ValueMapping(source = "ENDPOINT", target = "ENDPOINT")
   })
   SecretTypeEnum toResource(SecretType secretType);

   @ValueMappings({
         @ValueMapping(source = "ARTIFACT", target = "ARTIFACT"),
         @ValueMapping(source = "ENDPOINT", target = "ENDPOINT")
   })
   SecretType fromResource(SecretTypeEnum secretType);

   @ValueMappings({
         @ValueMapping(source = "REST", target = "REST"),
         @ValueMapping(source = "GRAPHQL", target = "GRAPHQL"),
         @ValueMapping(source = "GRPC", target = "GRPC")
   })
   ServiceTypeEnum toResource(ServiceType serviceType);

   @ValueMappings({
         @ValueMapping(source = "REST", target = "REST"),
         @ValueMapping(source = "GRAPHQL", target = "GRAPHQL"),
         @ValueMapping(source = "GRPC", target = "GRPC")
   })
   ServiceType fromResource(ServiceTypeEnum serviceTypeEnum);

   ServiceViewDTO toResource(Service service);

   ConfigurationTemplate fromResource(ConfigurationTemplateDTO configurationTemplateDTO);

   ConfigurationTemplateDTO toResource(ConfigurationTemplate configurationTemplate);

   List<ConfigurationTemplateDTO> toCTResources(List<ConfigurationTemplate> configurationTemplates);

   ConfigurationPlan fromResource(ConfigurationPlanDTO configurationPlanDTO);

   @Mapping(target = "serviceId", source = "service.id")
   @Mapping(target = "backendSecretId", source = "backendSecret.id")
   ConfigurationPlanDTO toResource(ConfigurationPlan configurationPlan);

   @AfterMapping
   default void hideSecuritySecrets(@MappingTarget ConfigurationPlanDTO configurationPlanDTO, ConfigurationPlan configurationPlan) {
      if (configurationPlan.apiKey != null) {
         configurationPlanDTO.apiKey = "*******";
      }
      if (configurationPlan.initialAccessToken != null) {
         configurationPlanDTO.initialAccessToken = "*******";
      }
   }

   List<ConfigurationPlanDTO> toCPResources(List<ConfigurationPlan> configurationPlans);

   ExpositionDTO toResource(Exposition exposition);

   ActiveExpositionDTO toResource(ActiveExposition activeExposition);

   List<ExpositionDTO> toEResources(List<Exposition> expositions);

   List<ActiveExpositionDTO> toAEResources(List<ActiveExposition> activeExpositions);

   QuotaDTO toResource(Quota quota);

   List<QuotaDTO> toResources(List<Quota> quotas);

   List<UserOrganizationDTO> toResource(List<Organization> organizations);
}
