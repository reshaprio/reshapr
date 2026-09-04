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

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.SecretType;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ConfigurationPlanManagerService}, focusing on the validation of the
 * per-plan artifact selection ({@code includedArtifacts}) on create and update. The selection references
 * attached artifacts by name; the service main artifact is never selectable.
 * @author laurent
 */
@QuarkusTest
// A CDI request context is required for every test because persistence goes through the multi-tenant
// ReshaprTenantResolver, which is @RequestScoped. Activating it at class level applies to all @Test methods
// (each test still isolates its data under a unique tenant seeded via ReshaprTenantContext).
@ActivateRequestContext
class ConfigurationPlanManagerServiceTest {

   private static final String MAIN_ARTIFACT = "openapi.yaml";
   private static final String PROMPTS_ARTIFACT = "prompts.yaml";
   private static final String TOOLS_ARTIFACT = "custom-tools.yaml";

   @Inject
   ConfigurationPlanManagerService managerService;

   @Inject
   ConfigurationPlanRepository configurationPlanRepository;

   @Test
   void testCreateWithValidArtifactsIsAccepted() throws Exception {
      String serviceId = seedServiceWithArtifacts();

      ConfigurationPlan plan = newPlan("valid-plan");
      plan.includedArtifacts = List.of(PROMPTS_ARTIFACT, TOOLS_ARTIFACT);

      ConfigurationPlan created = managerService.createConfigurationPlan(plan, serviceId, null, false);

      assertNotNull(created.id);
      assertEquals(List.of(PROMPTS_ARTIFACT, TOOLS_ARTIFACT), created.includedArtifacts);
      // Ensure it was persisted with the very same selection.
      ConfigurationPlan reloaded = loadPlan(created.id);
      assertEquals(List.of(PROMPTS_ARTIFACT, TOOLS_ARTIFACT), reloaded.includedArtifacts);
   }

   @Test
   void testCreateWithNullSelectionMeansAll() throws Exception {
      String serviceId = seedServiceWithArtifacts();

      ConfigurationPlan plan = newPlan("all-artifacts-plan");
      plan.includedArtifacts = null;

      ConfigurationPlan created = managerService.createConfigurationPlan(plan, serviceId, null, false);

      // A null/empty selection is normalized to an empty list, meaning "all attached artifacts apply".
      assertNotNull(created.includedArtifacts);
      assertTrue(created.includedArtifacts.isEmpty());
   }

   @Test
   void testCreateDeduplicatesSelection() throws Exception {
      String serviceId = seedServiceWithArtifacts();

      ConfigurationPlan plan = newPlan("dedup-plan");
      plan.includedArtifacts = List.of(PROMPTS_ARTIFACT, PROMPTS_ARTIFACT);

      ConfigurationPlan created = managerService.createConfigurationPlan(plan, serviceId, null, false);

      assertEquals(List.of(PROMPTS_ARTIFACT), created.includedArtifacts);
   }

   @Test
   void testCreateWithUnknownArtifactIsRejected() {
      String serviceId = seedServiceWithArtifacts();

      ConfigurationPlan plan = newPlan("invalid-plan");
      plan.includedArtifacts = List.of("does-not-exist.yaml");

      DependencyNotFoundException thrown = assertThrows(DependencyNotFoundException.class,
            () -> managerService.createConfigurationPlan(plan, serviceId, null, false));
      assertTrue(thrown.getMessage().contains("does-not-exist.yaml"));
   }

   @Test
   void testCreateSelectingMainArtifactIsRejected() {
      String serviceId = seedServiceWithArtifacts();

      // The main artifact is not an attached artifact and thus cannot be selected.
      ConfigurationPlan plan = newPlan("main-selected-plan");
      plan.includedArtifacts = List.of(MAIN_ARTIFACT);

      DependencyNotFoundException thrown = assertThrows(DependencyNotFoundException.class,
            () -> managerService.createConfigurationPlan(plan, serviceId, null, false));
      assertTrue(thrown.getMessage().contains(MAIN_ARTIFACT));
   }

   @Test
   void testUpdateWithValidArtifactsIsAccepted() throws Exception {
      String serviceId = seedServiceWithArtifacts();

      ConfigurationPlan created = managerService.createConfigurationPlan(newPlan("update-plan"), serviceId, null, false);

      ConfigurationPlan update = new ConfigurationPlan();
      update.id = created.id;
      update.name = created.name;
      update.backendEndpoint = created.backendEndpoint;
      update.includedArtifacts = List.of(TOOLS_ARTIFACT);

      ConfigurationPlan updated = managerService.updateConfigurationPlan(update, null);

      assertEquals(List.of(TOOLS_ARTIFACT), updated.includedArtifacts);
      assertEquals(List.of(TOOLS_ARTIFACT), loadPlan(created.id).includedArtifacts);
   }

   @Test
   void testUpdateWithUnknownArtifactIsRejected() throws Exception {
      String serviceId = seedServiceWithArtifacts();

      ConfigurationPlan created = managerService.createConfigurationPlan(newPlan("update-invalid-plan"), serviceId, null, false);

      ConfigurationPlan update = new ConfigurationPlan();
      update.id = created.id;
      update.name = created.name;
      update.backendEndpoint = created.backendEndpoint;
      update.includedArtifacts = List.of("ghost.yaml");

      assertThrows(DependencyNotFoundException.class,
            () -> managerService.updateConfigurationPlan(update, null));
   }

   @Test
   void testCreateWithElicitationSecretWithoutOAuthIsRejected() {
      String serviceId = seedServiceWithArtifacts();
      String secretId = seedSecret(true);

      ConfigurationPlan plan = newPlan("elicitation-no-oauth");
      // No oauth2Configuration set => not OAuth-protected.

      InvalidConfigurationException thrown = assertThrows(InvalidConfigurationException.class,
            () -> managerService.createConfigurationPlan(plan, serviceId, secretId, false));
      assertTrue(thrown.getMessage().contains("OAuth2 elicitation"));
   }

   @Test
   void testCreateWithElicitationSecretAndOAuthIsAccepted() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      String secretId = seedSecret(true);

      ConfigurationPlan plan = newPlan("elicitation-with-oauth");
      plan.oauth2Configuration = new ConfigurationPlan.OAuth2Configuration(
                        List.of("http://localhost:8080/auth"),
                        "http://localhost:8080/jwks",
                        List.of("read", "write"),
                        null,
                        false
                  );
      ConfigurationPlan created = managerService.createConfigurationPlan(plan, serviceId, secretId, false);
      assertNotNull(created.id);
      assertNotNull(created.backendSecret);
   }

   @Test
   void testCreateWithNonElicitationSecretWithoutOAuthIsAccepted() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      String secretId = seedSecret(false);

      // A backend secret that does not use elicitation does not require OAuth protection.
      ConfigurationPlan plan = newPlan("no-elicitation-no-oauth");

      ConfigurationPlan created = managerService.createConfigurationPlan(plan, serviceId, secretId, false);
      assertNotNull(created.id);
   }

   @Test
   void testUpdateAttachingElicitationSecretWithoutOAuthIsRejected() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      String secretId = seedSecret(true);

      ConfigurationPlan created = managerService.createConfigurationPlan(newPlan("update-elicitation"), serviceId, null, false);

      ConfigurationPlan update = new ConfigurationPlan();
      update.id = created.id;
      update.name = created.name;
      update.backendEndpoint = created.backendEndpoint;
      // Attach the elicitation secret but keep the plan unprotected.

      InvalidConfigurationException thrown = assertThrows(InvalidConfigurationException.class,
            () -> managerService.updateConfigurationPlan(update, secretId));
      assertTrue(thrown.getMessage().contains("OAuth2 elicitation"));
   }

   /**
    * Seeds a fresh Secret in the current tenant and returns its id. When {@code useElicitation} is true the
    * secret is flagged to trigger OAuth2 elicitation, which requires an OAuth-protected exposition.
    */
   private String seedSecret(boolean useElicitation) {
      return QuarkusTransaction.requiringNew().call(() -> {
         Secret secret = new Secret();
         secret.name = "secret-" + UUID.randomUUID();
         secret.type = SecretType.ENDPOINT;
         secret.useElicitation = useElicitation;
         secret.persist();
         return secret.id;
      });
   }

   /**
    * Builds a minimal, valid ConfigurationPlan template (id and selection set by the caller).
    */
   private ConfigurationPlan newPlan(String name) {
      ConfigurationPlan plan = new ConfigurationPlan();
      plan.name = name;
      plan.backendEndpoint = "https://backend.example.com";
      return plan;
   }

   /**
    * Seeds a fresh tenant with a service exposing one main artifact and two attached artifacts, then
    * returns the created service id. A unique organization is used per invocation so tests are isolated.
    */
   private String seedServiceWithArtifacts() {
      ReshaprTenantContext.setCurrentTenant("test-org-" + UUID.randomUUID());
      return QuarkusTransaction.requiringNew().call(() -> {
         Service service = new Service();
         service.name = "Test API";
         service.version = "1.0.0";
         service.type = ServiceType.REST;
         service.createdOn = OffsetDateTime.now();
         service.persist();

         persistArtifact(service, MAIN_ARTIFACT, ArtifactType.OPEN_API_SPEC, true);
         persistArtifact(service, PROMPTS_ARTIFACT, ArtifactType.RESHAPR_PROMPTS, false);
         persistArtifact(service, TOOLS_ARTIFACT, ArtifactType.RESHAPR_CUSTOM_TOOLS, false);
         return service.id;
      });
   }

   private void persistArtifact(Service service, String name, ArtifactType type, boolean mainArtifact) {
      Artifact artifact = new Artifact();
      artifact.name = name;
      artifact.type = type;
      artifact.mainArtifact = mainArtifact;
      artifact.content = "# content of " + name;
      artifact.sourceArtifact = name;
      artifact.service = service;
      artifact.persist();
   }

   private ConfigurationPlan loadPlan(String id) {
      return QuarkusTransaction.requiringNew().call(() -> configurationPlanRepository.findById(id));
   }
}
