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

import io.reshapr.ctrl.model.Gateway;
import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.SharedResource;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.ApiTokenRepository;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.repository.GatewayRepository;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.QuotaRepository;
import io.reshapr.ctrl.repository.SecretRepository;
import io.reshapr.ctrl.repository.ServiceRepository;
import io.reshapr.ctrl.repository.UserRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;
import io.reshapr.ctrl.security.ReshaprTenantResolver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * A Service responsible for offboarding organizations and users: the counterpart of
 * {@link OnboardingService}. It cascades the removal of an organization together with all its
 * dependent data (services and their artifacts / configuration plans / expositions, secrets,
 * gateways, gateway groups, quotas, API tokens, shared resources and user memberships), and the
 * removal of a user together with its API tokens and memberships.
 *
 * <p>Exposition removal is delegated to {@link ServiceManagerService#deleteService(String)} which
 * itself relies on {@link ConfigurationPlanManagerService} and {@link ExpositionManagerService},
 * so cluster-wide deletion events are broadcast to listening Gateways before rows are dropped.</p>
 *
 * <p>The {@code reshapr} root organization cannot be deleted, and its owner user is protected
 * from deletion as well.</p>
 * @author laurent
 */
@ApplicationScoped
public class OffboardingService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final OrganizationRepository organizationRepository;
   private final UserRepository userRepository;
   private final ServiceRepository serviceRepository;
   private final ServiceManagerService serviceManagerService;
   private final SecretRepository secretRepository;
   private final GatewayRepository gatewayRepository;
   private final GatewayGroupRepository gatewayGroupRepository;
   private final QuotaRepository quotaRepository;
   private final ApiTokenRepository apiTokenRepository;

   public OffboardingService(OrganizationRepository organizationRepository, UserRepository userRepository,
                             ServiceRepository serviceRepository, ServiceManagerService serviceManagerService,
                             SecretRepository secretRepository, GatewayRepository gatewayRepository,
                             GatewayGroupRepository gatewayGroupRepository, QuotaRepository quotaRepository,
                             ApiTokenRepository apiTokenRepository) {
      this.organizationRepository = organizationRepository;
      this.userRepository = userRepository;
      this.serviceRepository = serviceRepository;
      this.serviceManagerService = serviceManagerService;
      this.secretRepository = secretRepository;
      this.gatewayRepository = gatewayRepository;
      this.gatewayGroupRepository = gatewayGroupRepository;
      this.quotaRepository = quotaRepository;
      this.apiTokenRepository = apiTokenRepository;
   }

   /**
    * Delete the organization with the given name, cascading the removal of all its dependent
    * entities. Expositions attached to the organization services are removed with the standard
    * flow so a deletion event is broadcast to listening Gateways.
    * @param organizationName the name (which is also the tenant identifier) of the organization
    *                         to delete
    * @throws IllegalStateException if the caller tries to delete the {@code reshapr} root org
    * @throws DependencyNotFoundException if no organization with that name exists
    */
   @Transactional
   public void deleteOrganization(String organizationName) throws DependencyNotFoundException {
      logger.infof("Offboarding organization '%s' (with cascade)", organizationName);

      if (ReshaprTenantResolver.ROOT_TENANT_ID.equals(organizationName)) {
         throw new IllegalStateException("The 'reshapr' root organization cannot be deleted");
      }

      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         throw new DependencyNotFoundException("Organization " + organizationName + " not found");
      }
      String organizationId = organization.id;

      // Switch the tenant context so tenant-aware entity queries (Service, Secret, ...) target
      // the organization being deleted regardless of the admin caller's own tenant.
      String previousTenant = ReshaprTenantContext.getCurrentTenant();
      ReshaprTenantContext.setCurrentTenant(organizationName);
      try {
         // 1) Delete services via ServiceManagerService: this transitively removes the service
         //    artifacts, configuration plans and expositions (with proper propagation of the
         //    exposition deletion events to the Gateways).
         List<Service> services = serviceRepository.listAll();
         logger.infof("Removing %d service(s) of organization '%s'", services.size(), organizationName);
         for (Service service : services) {
            serviceManagerService.deleteService(service.id);
         }

         // 2) Finalize the deletion of the remaining tenant-scoped resources.
         secretRepository.delete("organizationId", organizationName);

         // Delete gateways one by one so Hibernate cleans up the gateways_gateway_groups join
         // rows through the @ManyToMany owning side. All gateways of the org are removed first,
         // so a bulk delete on gateway_groups afterwards no longer has orphan join rows to fear.
         for (Gateway gateway : gatewayRepository.list("organizationId", organizationName)) {
            gatewayRepository.delete(gateway);
         }
         gatewayGroupRepository.delete("organizationId", organizationName);

         quotaRepository.delete("organizationId", organizationName);
         apiTokenRepository.delete("organizationId", organizationName);
         SharedResource.delete("organizationId", organizationName);

         // 3) Detach users: drop memberships through the User.organizations owning side so
         //    Hibernate purges the users_organizations join rows, and reset any default_org
         //    reference pointing at this organization.
         List<User> members = userRepository.list("?1 member of organizations", organization);
         for (User member : members) {
            member.organizations.remove(organization);
         }
         List<User> usersWithDefault = userRepository.list("defaultOrganization.id", organizationId);
         for (User user : usersWithDefault) {
            user.defaultOrganization = null;
         }

         // 4) Finally drop the organization itself.
         organization.owner = null;
         organizationRepository.delete(organization);
      } finally {
         if (previousTenant != null) {
            ReshaprTenantContext.setCurrentTenant(previousTenant);
         } else {
            ReshaprTenantContext.clear();
         }
      }

      logger.infof("Organization '%s' offboarded successfully", organizationName);
   }

   /**
    * Delete the user with the given username, cascading the removal of its API tokens and
    * memberships. The organizations the user owned are kept but their {@code owner} reference is
    * cleared so an admin can later reassign them.
    *
    * <p>Users that own the {@code reshapr} root organization cannot be deleted to avoid locking
    * out the admin bootstrap account.</p>
    * @param username the username of the user to delete
    * @throws IllegalStateException if the user is the owner of the {@code reshapr} root org
    * @throws DependencyNotFoundException if no user with that username exists
    */
   @Transactional
   public void deleteUser(String username) throws DependencyNotFoundException {
      logger.infof("Offboarding user '%s' (with cascade)", username);

      User user = userRepository.findByUsername(username);
      if (user == null) {
         throw new DependencyNotFoundException("User " + username + " not found");
      }

      Organization reshaprOrg = organizationRepository.findByName(ReshaprTenantResolver.ROOT_TENANT_ID);
      if (reshaprOrg != null && reshaprOrg.owner != null && username.equals(reshaprOrg.owner.username)) {
         throw new IllegalStateException(
               "User '" + username + "' owns the 'reshapr' root organization and cannot be deleted");
      }

      // Switch the tenant context to root so we can list/manage entities across organizations
      // (api tokens live outside the tenant filter but organization ownership does not).
      String previousTenant = ReshaprTenantContext.getCurrentTenant();
      ReshaprTenantContext.setCurrentTenant(ReshaprTenantResolver.ROOT_TENANT_ID);
      try {
         // 1) Clear ownership references pointing at this user: the organizations remain but
         //    become unowned; an admin will reassign them later.
         List<Organization> ownedOrganizations = organizationRepository.list("owner.username", username);
         for (Organization organization : ownedOrganizations) {
            logger.debugf("Clearing owner of organization '%s' previously owned by '%s'",
                  organization.name, username);
            organization.owner = null;
         }

         // 2) Purge memberships through the User.organizations owning side and delete the user.
         user.organizations.clear();
         user.defaultOrganization = null;
         userRepository.delete(user);
      } finally {
         if (previousTenant != null) {
            ReshaprTenantContext.setCurrentTenant(previousTenant);
         } else {
            ReshaprTenantContext.clear();
         }
      }

      logger.infof("User '%s' offboarded successfully", username);
   }
}
