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
package io.reshapr.ctrl.service;

import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.repository.SecretRepository;
import io.reshapr.ctrl.repository.ServiceRepository;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ConfigurationPlanManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ConfigurationPlanRepository configurationPlanRepository;
   private final ServiceRepository serviceRepository;
   private final SecretRepository secretRepository;
   private final ExpositionManagerService expositionManagerService;
   private final ArtifactRepository artifactRepository;


   /**
    * Build a new ConfigurationPlanManagerService with the required repositories.
    * @param configurationPlanRepository The repository for managing configuration plans.
    * @param serviceRepository The repository for managing services.
    * @param secretRepository The repository for managing secrets.
    * @param expositionManagerService The service for managing expositions.
    * @param artifactRepository The repository for managing artifacts.
    *
    */
   public ConfigurationPlanManagerService(ConfigurationPlanRepository configurationPlanRepository, ServiceRepository serviceRepository,
                                          SecretRepository secretRepository, ExpositionManagerService expositionManagerService,
                                          ArtifactRepository artifactRepository) {
      this.configurationPlanRepository = configurationPlanRepository;
      this.serviceRepository = serviceRepository;
      this.secretRepository = secretRepository;
      this.expositionManagerService = expositionManagerService;
      this.artifactRepository = artifactRepository;
   }

   /**
    * Retrieves all configuration plans or those associated with a specific service ID.
    * @param serviceId The ID of the service for which to retrieve configuration plans, or null to retrieve all.
    * @return A list of configuration plans.
    */
   public List<ConfigurationPlan> getConfigurationPlans(@Nullable String serviceId) {
      if (serviceId == null) {
         logger.debug("Retrieving all configuration plans");
         return configurationPlanRepository.findAll().list();
      }
      logger.debugf("Retrieving configuration plans for service with id %s", serviceId);
      return configurationPlanRepository.findByServiceId(serviceId);
   }

   /**
    * Creates a new configuration plan.
    * @param configurationPlan the configuration plan to create
    * @param serviceId the ID of the service to associate with the configuration plan
    * @param backendSecretId the ID of the backend secret to associate with the configuration plan, can be null
    * @param useApiKey whether an API key must be generated for this configuration plan
    * @return the created configuration plan
    * @throws DependencyNotFoundException if the service or backend secret is not found
    * @throws InvalidConfigurationException if the plan uses OAuth2 elicitation without being OAuth-protected
    */
   @Transactional
   public ConfigurationPlan createConfigurationPlan(ConfigurationPlan configurationPlan, String serviceId,
                                                    @Nullable String backendSecretId, boolean useApiKey)
         throws DependencyNotFoundException, InvalidConfigurationException {
      logger.debugf("Creating configuration plan with name %s", configurationPlan.name);

      Service service = serviceRepository.findById(serviceId);
      if (service == null) {
         logger.errorf("Service with id %s not found", serviceId);
         throw new DependencyNotFoundException("Service with id " + serviceId + " not found");
      }
      configurationPlan.service = service;

      // Validate/normalize the selected artifacts (by name) against the service's attached artifacts.
      configurationPlan.includedArtifacts = validateIncludedArtifacts(serviceId, configurationPlan.includedArtifacts);

      if (backendSecretId != null) {
         logger.debugf("Setting backend secret with id %s for configuration plan %s", backendSecretId, configurationPlan.name);
         configurationPlan.backendSecret = secretRepository.findById(backendSecretId);
         if (configurationPlan.backendSecret == null) {
            logger.errorf("Backend secret with id %s not found", backendSecretId);
            throw new DependencyNotFoundException("Backend secret with id " + backendSecretId + " not found");
         }
      }
      if (useApiKey) {
         logger.debugf("Generating API token for configuration plan %s", configurationPlan.name);
         configurationPlan.apiKey = UUID.randomUUID().toString();
      } else {
         configurationPlan.apiKey = null;
      }

      // Enforce that an exposition relying on OAuth2 elicitation is OAuth-protected (required for
      // stateless MCP mode where the elicited secret is bound to the authenticated user identity).
      validateElicitationRequiresOAuth(configurationPlan);

      configurationPlanRepository.persistAndFlush(configurationPlan);
      return configurationPlan;
   }

   /**
    * Duplicates an existing configuration plan.
    * @param sourceId the ID of the configuration plan to duplicate
    * @param newName the name of the new configuration plan
    * @return the newly created configuration plan
    * @throws IllegalArgumentException if the source configuration plan is not found
    */
   @Transactional
   public ConfigurationPlan duplicateConfigurationPlan(String sourceId, String newName) {
      logger.debugf("Duplicating configuration plan with id %s to new name '%s'", sourceId, newName);
      ConfigurationPlan sourcePlan = configurationPlanRepository.findById(sourceId);
      if (sourcePlan == null) {
         logger.errorf("Source configuration plan with id %s not found", sourceId);
         throw new IllegalArgumentException("Source configuration plan with id " + sourceId + " not found");
      }

      ConfigurationPlan newPlan = new ConfigurationPlan();
      newPlan.name = newName;
      newPlan.description = sourcePlan.description;
      newPlan.service = sourcePlan.service;
      newPlan.backendEndpoint = sourcePlan.backendEndpoint;
      newPlan.backendTimeout = sourcePlan.backendTimeout;
      newPlan.includedOperations = sourcePlan.includedOperations != null ? new java.util.ArrayList<>(sourcePlan.includedOperations) : null;
      newPlan.excludedOperations = sourcePlan.excludedOperations != null ? new java.util.ArrayList<>(sourcePlan.excludedOperations) : null;
      newPlan.includedArtifacts = sourcePlan.includedArtifacts != null ? new java.util.ArrayList<>(sourcePlan.includedArtifacts) : null;
      newPlan.audit = sourcePlan.audit;
      newPlan.backendSecret = sourcePlan.backendSecret;

      // Duplicate OAuth2 configuration if present
      if (sourcePlan.oauth2Configuration != null) {
         newPlan.oauth2Configuration = new ConfigurationPlan.OAuth2Configuration(
               sourcePlan.oauth2Configuration.authorizationServers() != null ? new java.util.ArrayList<>(sourcePlan.oauth2Configuration.authorizationServers()) : null,
               sourcePlan.oauth2Configuration.jwksUri(),
               sourcePlan.oauth2Configuration.scopes() != null ? new java.util.ArrayList<>(sourcePlan.oauth2Configuration.scopes()) : null,
               sourcePlan.oauth2Configuration.staticAudiences() != null ? new java.util.ArrayList<>(sourcePlan.oauth2Configuration.staticAudiences()) : null,
               sourcePlan.oauth2Configuration.disableAudienceValidation()
         );
      }

      // Automatically generate new API key and/or initial access token if the source had them
      if (sourcePlan.apiKey != null) {
         logger.debugf("Generating new API token for duplicated configuration plan %s", newPlan.name);
         newPlan.apiKey = UUID.randomUUID().toString();
      }
      if (sourcePlan.initialAccessToken != null) {
         logger.debugf("Generating new initial access token for duplicated configuration plan %s", newPlan.name);
         newPlan.initialAccessToken = UUID.randomUUID().toString();
      }

      configurationPlanRepository.persistAndFlush(newPlan);
      return newPlan;
   }

   /**
    * Updates an existing configuration plan.
    * @param configurationPlan the configuration plan to update with fresh values
    * @param backendSecretId the ID of the backend secret to associate with the configuration plan, can be null
    * @return the updated configuration plan
    * @throws DependencyNotFoundException if the backend secret is not found
    * @throws InvalidConfigurationException if the plan uses OAuth2 elicitation without being OAuth-protected
    */
   @Transactional
   public ConfigurationPlan updateConfigurationPlan(ConfigurationPlan configurationPlan, @Nullable String backendSecretId)
         throws DependencyNotFoundException, InvalidConfigurationException {
      logger.debugf("Updating configuration plan with id %s", configurationPlan.id);
      ConfigurationPlan existingPlan = configurationPlanRepository.findById(configurationPlan.id);
      if (existingPlan == null) {
         logger.errorf("Configuration plan with id %s not found", configurationPlan.id);
         throw new IllegalArgumentException("Configuration plan with id " + configurationPlan.id + " not found");
      }
      existingPlan.name = configurationPlan.name;
      existingPlan.description = configurationPlan.description;
      existingPlan.backendEndpoint = configurationPlan.backendEndpoint;
      existingPlan.backendTimeout = configurationPlan.backendTimeout;
      existingPlan.includedOperations = configurationPlan.includedOperations;
      existingPlan.excludedOperations = configurationPlan.excludedOperations;
      // Validate/normalize the selected artifacts (by name) against the service's attached artifacts.
      existingPlan.includedArtifacts = validateIncludedArtifacts(existingPlan.service.id, configurationPlan.includedArtifacts);
      existingPlan.audit = configurationPlan.audit;
      if (backendSecretId != null) {
         logger.debugf("Setting backend secret with id %s for configuration plan %s", backendSecretId, existingPlan.name);
         existingPlan.backendSecret = secretRepository.findById(backendSecretId);
         if (existingPlan.backendSecret == null) {
            logger.errorf("Backend secret with id %s not found", backendSecretId);
            throw new DependencyNotFoundException("Backend secret with id " + backendSecretId + " not found");
         }
      } else {
         existingPlan.backendSecret = null;
      }
      existingPlan.oauth2Configuration = configurationPlan.oauth2Configuration;

      // Enforce that an exposition relying on OAuth2 elicitation is OAuth-protected (required for
      // stateless MCP mode where the elicited secret is bound to the authenticated user identity).
      validateElicitationRequiresOAuth(existingPlan);

      configurationPlanRepository.persistAndFlush(existingPlan);
      expositionManagerService.propagateConfigurationPlanChanges(existingPlan);
      return existingPlan;
   }

   /**
    * Renews the API key for a given configuration plan.
    * @param configurationPlan the configuration plan for which to renew the API key
    * @return the updated configuration plan with a new API key
    */
   @Transactional
   public ConfigurationPlan renewApiKey(ConfigurationPlan configurationPlan) {
      logger.debugf("Renewing API token for configuration plan with id %s", configurationPlan.id);
      ConfigurationPlan existingPlan = configurationPlanRepository.findById(configurationPlan.id);
      if (existingPlan == null) {
         logger.errorf("Configuration plan with id %s not found", configurationPlan.id);
         throw new IllegalArgumentException("Configuration plan with id " + configurationPlan.id + " not found");
      }
      existingPlan.apiKey = UUID.randomUUID().toString();
      configurationPlanRepository.persistAndFlush(existingPlan);
      expositionManagerService.propagateConfigurationPlanChanges(existingPlan);
      return existingPlan;
   }

   /**
    * Deletes a configuration plan and its related expositions.
    * @param configurationPlan the configuration plan to delete
    */
   @Transactional
   public void deleteConfigurationPlan(ConfigurationPlan configurationPlan) {
      // Delete related expositions first.
      expositionManagerService.removeConfigurationPlanExpositions(configurationPlan.id);
      // Then delete the configuration plan itself.
      configurationPlanRepository.delete(configurationPlan);
   }

   /**
    * Validates and normalizes the {@code includedArtifacts} selection of a plan. Each entry must match, by
    * name, an attached artifact of the service. Duplicates are removed while preserving order. A null or
    * empty selection is returned as an empty list, meaning "all attached artifacts of the service apply".
    * @param serviceId The id of the service owning the plan.
    * @param includedArtifacts The requested artifact names (may be null or empty).
    * @return The validated, de-duplicated list of artifact names (never null).
    * @throws DependencyNotFoundException if a name does not match any attached artifact of the service.
    */
   private List<String> validateIncludedArtifacts(String serviceId, @Nullable List<String> includedArtifacts)
         throws DependencyNotFoundException {
      if (includedArtifacts == null || includedArtifacts.isEmpty()) {
         return new ArrayList<>();
      }
      Set<String> attachedNames = artifactRepository.findAttachedNamesByServiceId(serviceId);
      // Keep insertion order and drop duplicates.
      Set<String> selected = new LinkedHashSet<>(includedArtifacts);
      for (String name : selected) {
         if (!attachedNames.contains(name)) {
            logger.errorf("Artifact '%s' is not an attached artifact of service '%s'", name, serviceId);
            throw new DependencyNotFoundException("Artifact '" + name
                  + "' is not an attached artifact of the service and cannot be selected");
         }
      }
      return new ArrayList<>(selected);
   }

   /**
    * Enforces the elicitation/OAuth consistency rule: a configuration plan whose backend secret
    * relies on OAuth2 elicitation ({@code useElicitation == true}) must expose an inbound OAuth2 protection
    * ({@link ConfigurationPlan#oauth2Configuration} present). This is mandatory because, in the stateless MCP
    * mode ({@code 2026-07-28}), there is no server-side session to hold the elicited secret: it is bound to
    * the authenticated user identity (JWT {@code iss}+{@code sub}), which only exists when the exposition is
    * OAuth-protected. In the legacy (session-based) mode the session would suffice, but the control plane
    * cannot know at configuration time which mode a client will negotiate, so the stricter invariant is
    * enforced up-front to guarantee the stateless path is always safe.
    * @param plan The configuration plan being created or updated (with its backend secret already resolved).
    * @throws InvalidConfigurationException if the plan uses elicitation without an OAuth2 configuration.
    */
   private void validateElicitationRequiresOAuth(ConfigurationPlan plan) throws InvalidConfigurationException {
      if (plan.backendSecret != null && plan.backendSecret.useElicitation && plan.oauth2Configuration == null) {
         logger.errorf("Configuration plan '%s' uses OAuth2 elicitation (secret '%s') but is not OAuth-protected",
               plan.name, plan.backendSecret.name);
         throw new InvalidConfigurationException("Configuration plan '" + plan.name
               + "' relies on OAuth2 elicitation (backend secret '" + plan.backendSecret.name
               + "') and must be protected by an inbound OAuth2 configuration: elicitation in stateless MCP"
               + " mode binds the collected secret to the authenticated user identity, which requires the"
               + " exposition to be OAuth-protected.");
      }
   }
}
