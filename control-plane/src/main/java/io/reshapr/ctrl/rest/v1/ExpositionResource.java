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
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.repository.ExpositionRepository;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.EntityAlreadyExistException;
import io.reshapr.ctrl.service.ExpositionManagerService;

import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/api/v1/expositions")
public class ExpositionResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ExpositionManagerService expositionManagerService;
   private final Mappers v1Mappers;

   /**
    * Constructor for ExpositionResource.
    * @param expositionManagerService the service to manage expositions
    * @param v1Mappers the mappers for converting between DTOs and entities
    */
   public ExpositionResource(ExpositionManagerService expositionManagerService, ExpositionRepository expositionRepository, Mappers v1Mappers) {
      this.expositionManagerService = expositionManagerService;
      this.v1Mappers = v1Mappers;
   }

   @POST
   @Authenticated
   @Produces(MediaType.APPLICATION_JSON)
   public Response createExposition(ExpositionReferenceDTO expositionDTO) {
      logger.infof("Creating a new exposition for config plan '%s' on gateway group '%s'",
            expositionDTO.configurationPlanId(), expositionDTO.gatewayGroupId());

      try {
         // The optional exposition name is provided by the caller (CLI/Web UI may propose a default slug);
         // when null/blank the exposition is created unnamed.
         Exposition exposition = expositionManagerService.exposeConfiguration(
               expositionDTO.configurationPlanId(), expositionDTO.gatewayGroupId(), expositionDTO.name());
         return Response.status(Response.Status.CREATED).entity(
               v1Mappers.toResource(exposition)).build();
      } catch (DependencyNotFoundException dnfe) {
         return Response.status(Response.Status.NOT_FOUND).entity(dnfe.getMessage()).build();
      } catch (EntityAlreadyExistException eaee) {
         return Response.status(Response.Status.CONFLICT).entity(eaee.getMessage()).build();
      }
   }

   @GET
   @Authenticated
   public List<ExpositionDTO> listExpositions(@QueryParam("serviceId") String serviceId,
                                   @QueryParam("gatewayGroupId") String gatewayGroupId) {
      List<Exposition> expositions = expositionManagerService.getExpositions(serviceId, gatewayGroupId);
      return v1Mappers.toEResources(expositions);
   }

   @GET
   @Authenticated
   @Path("/active")
   public List<ActiveExpositionDTO> listActiveExpositions() {
      List<ActiveExposition> activeExpositions = expositionManagerService.getActiveExpositions();
      return v1Mappers.toAEResources(activeExpositions);
   }

   @GET
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getExposition(@PathParam("id") String id) {
      Exposition exposition = expositionManagerService.getExposition(id);
      if (exposition == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return Response.ok().entity(v1Mappers.toResource(exposition)).build();
   }

   @GET
   @Authenticated
   @Path("/active/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getActiveExposition(@PathParam("id") String id) {
      ActiveExposition activeExposition = expositionManagerService.getActiveExposition(id);
      if (activeExposition == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return Response.ok().entity(v1Mappers.toResource(activeExposition)).build();
   }

   @DELETE
   @Authenticated
   @Path("/{id}")
   public Response deleteExposition(@PathParam("id") String id) {
      logger.infof("Deleting exposition with id '%s'", id);
      try {
         expositionManagerService.removeExposition(id);
         return Response.noContent().build();
      } catch (DependencyNotFoundException dnfe) {
         return Response.status(Response.Status.NOT_FOUND).entity(dnfe.getMessage()).build();
      }
   }
}
