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

import io.quarkus.panache.common.Sort;
import io.quarkus.panache.common.Page;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/api/admin/organizations")
@AdminAuthenticated
public class OrganizationResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final OnboardingService onboardingService;
   private final UserRepository userRepository;
   private final OrganizationRepository organizationRepository;

   /**
    * Build an OrganizationResource with required dependencies.
    * @param onboardingService The OnboardingService to handle organization creation logic.
    * @param organizationRepository The Organization repository
    * @param userRepository The User repository
    */
   public OrganizationResource(OnboardingService onboardingService, OrganizationRepository organizationRepository, UserRepository userRepository) {
      this.onboardingService = onboardingService;
      this.organizationRepository = organizationRepository;
      this.userRepository = userRepository;
   }

   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public List<FullOrganizationDTO> getOrganizations(@QueryParam("page") @DefaultValue("0") int page,
         @QueryParam("size") @DefaultValue("20") int size) {
      return organizationRepository.findAll(Sort.ascending("name")).page(Page.of(page, size))
            .stream()
            .map(org -> new FullOrganizationDTO(
                  org.name,
                  org.description,
                  org.icon,
                  org.owner != null ? org.owner.username : null
            ))
            .toList();
   }

   @POST
   @Produces(MediaType.APPLICATION_JSON)
   public Response createOrganization(@Valid OrganizationDTO organizationDTO) {
      try {
         Organization organization = onboardingService.createUnassignedOrganization(new OnboardingService.OrganizationInfo(
               organizationDTO.name(), organizationDTO.description(), organizationDTO.icon()));

         // Don't forget to initialize quotas.
         onboardingService.initializeOnboardingQuotas(organization.name);

         return Response.status(Response.Status.CREATED)
               .entity(new OrganizationDTO(organization.name, organization.description, organization.icon))
               .build();
      } catch (EntityAlreadyExistException _) {
         logger.warnf("Organization with name '%s' already exists", organizationDTO.name());
         return Response.status(Response.Status.CONFLICT.getStatusCode(), "Organization already exists").build();
      } catch (DependencyNotFoundException e) {
         logger.warnf("Organization with name '%s' quotas are not initialized", organizationDTO.name());
         return Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage()).build();
      }
   }

   @PUT
   @Path("/{organizationName}/owner")
   @Produces(MediaType.APPLICATION_JSON)
   @Transactional
   public Response changeOwner(@PathParam("organizationName") String organizationName, String username) {
      // Find user by username.
      User user = userRepository.findByUsername(username);
      if (user == null) {
         logger.warnf("User with username %s not found", username);
         return Response.status(Response.Status.NOT_FOUND).entity("User not found").build();
      }

      // Check if organization already exists.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         logger.warnf("Organization with name %s not found", organizationName);
         return Response.status(Response.Status.NOT_FOUND).entity("Organization not found").build();
      }

      // Assign organization to user.
      if (!user.organizations.contains(organization)) {
         user.organizations.add(organization);
      }
      user.ensureDefaultOrganization();
      userRepository.persistAndFlush(user);
      // Change owner on organization side.
      organization.owner = user;
      organizationRepository.persistAndFlush(organization);

      return Response.ok(new FullOrganizationDTO(organizationName, organization.description, organization.icon, username)).build();
   }
}
