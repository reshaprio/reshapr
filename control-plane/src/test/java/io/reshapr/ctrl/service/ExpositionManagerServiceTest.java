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
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.QuotaMetric;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.security.ReshaprTenantResolver;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.reshapr.ctrl.security.ReshaprTenantContext;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ExpositionManagerService#exposeConfiguration}, focusing on the two
 * organization-scoped uniqueness constraints: the service + gateway group + configuration plan triple must
 * not be exposed twice, and the exposition name must be unique within the organization. Both are surfaced as
 * an {@link EntityAlreadyExistException} before hitting the database unique indexes.
 * @author laurent
 */
@QuarkusTest
class ExpositionManagerServiceTest {

   @Inject
   ExpositionManagerService managerService;

   @Inject
   CurrentIdentityAssociation identityAssociation;

   @Test
   void testExposingSameTripleTwiceIsRejected() throws Throwable {
      Fixture fixture = seedFixture();

      Exposition first = expose(fixture, fixture.planId, "first-exposition");
      assertNotNull(first.id);

      // Re-exposing the very same configuration plan on the same gateway group must be rejected.
      EntityAlreadyExistException thrown = assertThrows(EntityAlreadyExistException.class,
            () -> expose(fixture, fixture.planId, "second-name"));
      assertTrue(thrown.getMessage().contains(fixture.planId));
      assertTrue(thrown.getMessage().contains(fixture.gatewayGroupId));
   }

   @Test
   void testReusingExpositionNameIsRejected() throws Throwable {
      Fixture fixture = seedFixture();

      Exposition first = expose(fixture, fixture.planId, "shared-name");
      assertNotNull(first.id);

      // A second, distinct configuration plan reusing an already-taken name must be rejected.
      String otherPlanId = seedConfigurationPlan(fixture, "other-plan");
      EntityAlreadyExistException thrown = assertThrows(EntityAlreadyExistException.class,
            () -> expose(fixture, otherPlanId, "shared-name"));
      assertTrue(thrown.getMessage().contains("shared-name"));
   }

   /**
    * Invokes {@link ExpositionManagerService#exposeConfiguration} on a Vertx duplicated context carrying the
    * fixture's organization id as the tenant local. This mirrors what the request pipeline does at runtime
    * and is required both by the {@code EXPOSITION_COUNT} quota interceptor and by the gateway group
    * resolution, which reads the tenant from the Vertx context.
    */
   private Exposition expose(Fixture fixture, String planId, String name) throws Throwable {
      return VertxContextSupport.subscribeAndAwait(() -> VertxContextSupport.executeBlocking(() -> {
         Vertx.currentContext().putLocal(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY, fixture.organizationId);
         // The EXPOSITION_COUNT quota interceptor resolves the organization from the security identity, so
         // provide an authenticated identity carrying the fixture organization id for the active request.
         identityAssociation.setIdentity(QuarkusSecurityIdentity.builder()
               .setPrincipal((Principal) () -> "test-user")
               .addAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY, fixture.organizationId)
               .build());
         return managerService.exposeConfiguration(planId, fixture.gatewayGroupId, name);
      }));
   }

   /**
    * Seeds a fresh, isolated organization with an enabled EXPOSITION_COUNT quota, a service with one
    * configuration plan and a gateway group owned by that organization.
    */
   private Fixture seedFixture() {
      Fixture fixture = new Fixture();
      fixture.organizationId = "test-org-" + UUID.randomUUID();
      // Seed under the fixture tenant so @TenantId columns are populated with the fixture organization.
      ReshaprTenantContext.setCurrentTenant(fixture.organizationId);
      QuarkusTransaction.requiringNew().run(() -> {
         Quota quota = new Quota();
         quota.organizationId = fixture.organizationId;
         quota.metric = QuotaMetric.EXPOSITION_COUNT.toString();
         quota.enabled = true;
         quota.limit = 100L;
         quota.remaining = 100L;
         quota.persist();

         GatewayGroup gatewayGroup = new GatewayGroup();
         gatewayGroup.name = "group-" + UUID.randomUUID();
         gatewayGroup.organizationId = fixture.organizationId;
         gatewayGroup.persist();
         fixture.gatewayGroupId = gatewayGroup.id;

         Service service = new Service();
         service.name = "Test API";
         service.version = "1.0.0";
         service.type = ServiceType.REST;
         service.organizationId = fixture.organizationId;
         service.createdOn = OffsetDateTime.now();
         service.persist();
         fixture.serviceId = service.id;

         ConfigurationPlan plan = new ConfigurationPlan();
         plan.name = "default-plan";
         plan.backendEndpoint = "https://backend.example.com";
         plan.organizationId = fixture.organizationId;
         plan.service = service;
         plan.persist();
         fixture.planId = plan.id;
      });
      return fixture;
   }

   private String seedConfigurationPlan(Fixture fixture, String name) {
      ReshaprTenantContext.setCurrentTenant(fixture.organizationId);
      return QuarkusTransaction.requiringNew().call(() -> {
         Service service = Service.findById(fixture.serviceId);
         ConfigurationPlan plan = new ConfigurationPlan();
         plan.name = name;
         plan.backendEndpoint = "https://backend.example.com";
         plan.organizationId = fixture.organizationId;
         plan.service = service;
         plan.persist();
         return plan.id;
      });
   }

   private static final class Fixture {
      String organizationId;
      String serviceId;
      String planId;
      String gatewayGroupId;
   }
}
