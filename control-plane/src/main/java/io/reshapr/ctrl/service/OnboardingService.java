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

import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.model.UserStatus;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.QuotaRepository;
import io.reshapr.ctrl.repository.UserRepository;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Service responsible for onboarding users and organizations.
 * @author laurent
 */
@ApplicationScoped
public class OnboardingService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final UserRepository userRepository;
   private final OrganizationRepository organizationRepository;
   private final QuotaRepository quotaRepository;

   @ConfigProperty(name = "resharp.ctrl.onboarding-quotas")
   Map<String, Long> onboardingQuotas;

   /**
    * Build a OnbaordingService with required dependencies.
    * @param userRepository The repository to access user data.
    * @param organizationRepository The repository to access organization data.
    * @param quotaRepository The repository to access quota data.
    */
   public OnboardingService(UserRepository userRepository, OrganizationRepository organizationRepository, QuotaRepository quotaRepository) {
      this.userRepository = userRepository;
      this.organizationRepository = organizationRepository;
      this.quotaRepository = quotaRepository;
   }

   @Transactional
   public User createUser(UserInfo userInfo) throws EntityAlreadyExistException {
      logger.infof("Creating user with username: %s", userInfo.username());

      // Check if user already exists.
      User user = userRepository.findByUsername(userInfo.username());
      if (user != null) {
         logger.warnf("User with username %s already exists", userInfo.username());
         throw new EntityAlreadyExistException("User " + userInfo.username() + " already exists");
      }

      user = new User();
      user.username = userInfo.username();
      user.email = userInfo.email();
      if (userInfo.password() != null && !userInfo.password().isBlank()) {
         user.password = BcryptUtil.bcryptHash(userInfo.password());
      }
      if (userInfo.firstname() != null) {
         user.firstname = userInfo.firstname();
      }
      if (userInfo.lastname() != null) {
         user.lastname = userInfo.lastname();
      }
      user.status = UserStatus.REGISTERED;
      userRepository.persistAndFlush(user);
      return user;
   }

   @Transactional
   public Organization createOrganization(String username, OrganizationInfo organizationInfo) throws DependencyNotFoundException, EntityAlreadyExistException {
      logger.infof("Creating organization %s for user %s", organizationInfo.name(), username);

      // Find user by username.
      User user = userRepository.findByUsername(username);
      if (user == null) {
         logger.warnf("User with username %s not found", username);
         throw new DependencyNotFoundException("User not found");
      }

      // Check if organization already exists.
      Organization organization = organizationRepository.findByName(organizationInfo.name());
      if (organization != null) {
         logger.warnf("Organization with name %s already exists", organizationInfo.name());
         throw new EntityAlreadyExistException("Organization " + organizationInfo.name() + " already exists");
      }

      // Create and persist organization.
      organization = new Organization();
      organization.name = organizationInfo.name();
      organization.description = organizationInfo.description();
      organization.icon = organizationInfo.icon();
      organization.owner = user;
      organizationRepository.persistAndFlush(organization);

      // Assign organization to user.
      if (user.organizations == null) {
         user.organizations = new ArrayList<>();
      }
      user.organizations.add(organization);
      user.ensureDefaultOrganization();
      userRepository.persistAndFlush(user);
      return organization;
   }

   @Transactional
   public Organization createUnassignedOrganization(OrganizationInfo organizationInfo) throws EntityAlreadyExistException {
      logger.infof("Creating organization %s", organizationInfo.name());

      // Check if organization already exists.
      Organization organization = organizationRepository.findByName(organizationInfo.name());
      if (organization != null) {
         logger.warnf("Organization with name %s already exists", organizationInfo.name());
         throw new EntityAlreadyExistException("Organization " + organizationInfo.name() + " already exists");
      }

      // Create and persist organization.
      organization = new Organization();
      organization.name = organizationInfo.name();
      organization.description = organizationInfo.description();
      organization.icon = organizationInfo.icon();
      organizationRepository.persistAndFlush(organization);
      return organization;
   }

   @Transactional
   public void initializeOnboardingQuotas(String organizationName) throws DependencyNotFoundException {
      logger.infof("Initializing quotas for organization %s", organizationName);

      // Find organization by name.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         logger.warnf("Organization with name %s not found", organizationName);
         throw new DependencyNotFoundException("Organization " + organizationName + " not found");
      }

      // Assign onboarding quotas.
      List<Quota> quotas = new ArrayList<>();
      for (Map.Entry<String, Long> entry : onboardingQuotas.entrySet()) {
         Quota quota = new Quota();
         quota.organizationId = organizationName;
         quota.metric = entry.getKey();
         quota.enabled = true;
         quota.limit = entry.getValue();
         quota.remaining = entry.getValue();
         quotas.add(quota);
      }
      quotaRepository.persist(quotas);
   }

   @RegisterForReflection
   public record UserInfo(String username, String email, String password, String firstname, String lastname) {}

   @RegisterForReflection
   public record OrganizationInfo(String name, String description, String icon) {}
}
