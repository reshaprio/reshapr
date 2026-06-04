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
package io.reshapr.ctrl.rest.admin;

import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.UserRepository;
import io.reshapr.ctrl.security.AdminAuthenticated;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.EntityAlreadyExistException;
import io.reshapr.ctrl.service.OnboardingService;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/api/admin/users")
@AdminAuthenticated
public class UserResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final OnboardingService onboardingService;
   private final UserRepository userRepository;
   private final OrganizationRepository organizationRepository;

   /**
    * Build a UserResource with required dependencies.
    * @param onboardingService The OnboardingService to handle user and organization creation logic.
    * @param userRepository The User repository
    * @param organizationRepository The Organization repository
    */
   public UserResource(OnboardingService onboardingService, UserRepository userRepository, OrganizationRepository organizationRepository) {
      this.onboardingService = onboardingService;
      this.userRepository = userRepository;
      this.organizationRepository = organizationRepository;
   }

   @GET
   public List<UserDTO> getUsers(@QueryParam("page") @DefaultValue("0") int page,
                                        @QueryParam("size") @DefaultValue("20") int size) {
      return userRepository.findAll(Sort.ascending("username")).page(Page.of(page, size))
            .stream()
            .map(user -> new UserDTO(
                  user.username, user.email,
                  user.firstname, user.lastname,
                  user.defaultOrganization != null ? user.defaultOrganization.name : null
            ))
            .toList();
   }

   @POST
   public Response createUser(@Valid UserRequestDTO userDTO) {
      User user = null;
      try {
         user = onboardingService.createUser(new OnboardingService.UserInfo(
            userDTO.username(), userDTO.email(), userDTO.password(),
               userDTO.firstname(), userDTO.lastname()
         ));
      } catch (EntityAlreadyExistException _) {
         logger.warnf("User with username %s already exists", userDTO.username());
         return Response.status(Response.Status.CONFLICT.getStatusCode(), "User already exists").build();
      }
      return Response.status(Response.Status.CREATED).entity(user).build();
   }

   @POST
   @Path("/{username}/organization")
   public Response createOrganization(@PathParam("username") String username, @Valid OrganizationDTO organizationDTO) {
      try {
         onboardingService.createOrganization(username, new OnboardingService.OrganizationInfo(
               organizationDTO.name(), organizationDTO.description(), organizationDTO.icon()));
      } catch (DependencyNotFoundException _) {
         logger.warnf("User with username %s not found", username);
         return Response.status(Response.Status.NOT_FOUND.getStatusCode(), "User not found").build();
      } catch (EntityAlreadyExistException _) {
         logger.warnf("Organization with name %s already exists", organizationDTO.name());
         return Response.status(Response.Status.CONFLICT.getStatusCode(), "Organization already exists").build();
      }
      return Response.status(Response.Status.CREATED).entity(organizationDTO).build();
   }

   @PUT
   @Path("/{username}/organization/{organizationName}")
   @Transactional
   public Response updateOrganization(@PathParam("username") String username, @PathParam("organizationName") String organizationName,
                                      @Valid OrganizationDTO organizationDTO) {
      logger.infof("Updating organization %s for user %s", organizationName, username);

      // Find user by username.
      User user = userRepository.findByUsername(username);
      if (user == null) {
         logger.warnf("User with username %s not found", username);
         return Response.status(Response.Status.NOT_FOUND.getStatusCode(), "User not found").build();
      }

      // Find organization by name.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null || !organization.owner.username.equals(username)) {
         logger.warnf("Organization with name %s not found or not owned by user %s", organizationName, username);
         return Response.status(Response.Status.NOT_FOUND.getStatusCode(), "Organization not found or not owned by user").build();
      }

      // Update and persist organization.
      organization.name = organizationDTO.name();
      organization.description = organizationDTO.description();
      organization.icon = organizationDTO.icon();
      organizationRepository.persistAndFlush(organization);

      return Response.ok(organizationDTO).build();
   }

   @PUT
   @Path("/{username}/organization/{organizationName}/owner")
   @Transactional
   public Response updateOrganizationOwner(@PathParam("username") String username, @PathParam("organizationName") String organizationName) {
      logger.infof("Updating organization owner for organization %s to user %s", organizationName, username);

      // Find user by username.
      User user = userRepository.findByUsername(username);
      if (user == null) {
         logger.warnf("User with username %s not found", username);
         return Response.status(Response.Status.NOT_FOUND.getStatusCode(), "User not found").build();
      }

      // Find organization by name.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         logger.warnf("Organization with name %s not found", organizationName);
         return Response.status(Response.Status.NOT_FOUND.getStatusCode(), "Organization not found").build();
      }

      // Update and persist user and organization.
      organization.owner = user;
      organizationRepository.persistAndFlush(organization);

      // Assign organization to user if not already a member.
      if (user.organizations.stream().noneMatch(o -> o.name.equals(organizationName))) {
         user.organizations.add(organization);
      }
      user.ensureDefaultOrganization();
      userRepository.persistAndFlush(user);

      return Response.ok(new OrganizationDTO(organizationName, organization.description, organization.icon)).build();
   }

   @PUT
   @Path("/{username}/memberships")
   @Transactional
   public Response assignMembership(@PathParam("username") String username, List<String> organisationIds) {
      logger.infof("Assigning memberships %s to user %s", organisationIds, username);

      // Find user by username.
      User user = userRepository.findByUsername(username);
      if (user == null) {
         logger.warnf("User with username %s not found", username);
         return Response.status(Response.Status.NOT_FOUND.getStatusCode(), "User not found").build();
      }

      // Clear existing organizations and assign new ones.
      user.organizations.clear();
      user.organizations = organizationRepository.findByNames(organisationIds);
      user.ensureDefaultOrganization();
      userRepository.persistAndFlush(user);

      return Response.ok(organisationIds).build();
   }
}
