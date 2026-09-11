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

import io.quarkus.security.Authenticated;
import io.reshapr.ctrl.model.ConfigurationTemplate;
import io.reshapr.ctrl.service.ConfigurationTemplateManagerService;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * REST resource for managing configuration templates.
 * Provides CRUD endpoints under {@code /api/v1/configurationTemplates}.
 *
 * @author vaishnav
 */
@RunOnVirtualThread
@Path("/api/v1/configurationTemplates")
public class ConfigurationTemplateResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ConfigurationTemplateManagerService managerService;
   private final Mappers v1Mappers;

   public ConfigurationTemplateResource(ConfigurationTemplateManagerService managerService, Mappers v1Mappers) {
      this.managerService = managerService;
      this.v1Mappers = v1Mappers;
   }

   /**
    * Returns all configuration templates for the current tenant.
    * @return a list of template DTOs
    */
   @GET
   @Authenticated
   @Produces(MediaType.APPLICATION_JSON)
   public List<ConfigurationTemplateDTO> getConfigurationTemplates() {
      logger.debug("Retrieving all configuration templates");
      return v1Mappers.toCTResources(managerService.getConfigurationTemplates());
   }

   /**
    * Returns a single configuration template by ID.
    * @param id the template ID
    * @return 200 with the template DTO, or 404 if not found
    */
   @GET
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getConfigurationTemplate(@PathParam("id") String id) {
      logger.debugf("Retrieving configuration template with id '%s'", id);
      ConfigurationTemplate template = managerService.getConfigurationTemplate(id);
      if (template == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return Response.ok(v1Mappers.toResource(template)).build();
   }

   /**
    * Creates a new configuration template.
    * @param dto the template definition
    * @return 201 with the created template DTO, or 409 if the name conflicts
    */
   @POST
   @Authenticated
   @Produces(MediaType.APPLICATION_JSON)
   public Response createConfigurationTemplate(@Valid ConfigurationTemplateDTO dto) {
      logger.infof("Creating a new configuration template named '%s'", dto.name());
      ConfigurationTemplate template = v1Mappers.fromResource(dto);
      try {
         template = managerService.createConfigurationTemplate(template);
      } catch (IllegalArgumentException e) {
         logger.errorf("Failed to create configuration template: %s", e.getMessage());
         return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
      }
      return Response.status(Response.Status.CREATED).entity(v1Mappers.toResource(template)).build();
   }

   /**
    * Updates an existing configuration template.
    * @param id the ID of the template to update
    * @param dto the new template values
    * @return 200 with the updated template DTO, 404 if not found, or 409 if the name conflicts
    */
   @PUT
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response updateConfigurationTemplate(@PathParam("id") String id, @Valid ConfigurationTemplateDTO dto) {
      logger.infof("Updating configuration template with id '%s'", id);
      ConfigurationTemplate incoming = v1Mappers.fromResource(dto);
      try {
         ConfigurationTemplate updated = managerService.updateConfigurationTemplate(id, incoming);
         return Response.ok(v1Mappers.toResource(updated)).build();
      } catch (IllegalArgumentException e) {
         logger.errorf("Failed to update configuration template: %s", e.getMessage());
         // Distinguish "not found" from "name conflict" by message content.
         if (e.getMessage().contains("not found")) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
         }
         return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
      }
   }

   /**
    * Deletes a configuration template by ID.
    * @param id the ID of the template to delete
    * @return 204 on success, 404 if not found
    */
   @DELETE
   @Authenticated
   @Path("/{id}")
   public Response deleteConfigurationTemplate(@PathParam("id") String id) {
      logger.infof("Deleting configuration template with id '%s'", id);
      try {
         managerService.deleteConfigurationTemplate(id);
      } catch (IllegalArgumentException e) {
         logger.errorf("Failed to delete configuration template: %s", e.getMessage());
         return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
      }
      return Response.noContent().build();
   }
}
