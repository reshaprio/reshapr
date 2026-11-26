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

import io.reshapr.ctrl.control.QuotaRestricted;
import io.reshapr.ctrl.control.ReleaseQuota;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.QuotaMetric;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.security.ReshaprTenantResolver;
import io.reshapr.ctrl.service.GatewayGroupManagerService;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
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
 * GatewayGroupResource provides REST endpoints for managing gateway groups.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/api/v1/gatewayGroups")
public class GatewayGroupResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final GatewayGroupManagerService managerService;
   private final GatewayGroupRepository gatewayGroupRepository;
   private final Mappers v1Mappers;
   private final SecurityIdentity securityIdentity;

   public GatewayGroupResource(GatewayGroupManagerService managerService, GatewayGroupRepository gatewayGroupRepository,
                               Mappers v1Mappers, SecurityIdentity securityIdentity) {
      this.managerService = managerService;
      this.gatewayGroupRepository = gatewayGroupRepository;
      this.v1Mappers = v1Mappers;
      this.securityIdentity = securityIdentity;
   }

   @GET
   @Authenticated
   public List<GatewayGroupDTO> getGatewayGroups() {
      List<GatewayGroup> gatewayGroups = managerService.getAvailableGatewayGroups();
      return v1Mappers.toGGResources(gatewayGroups);
   }

   @GET
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getGatewayGroups(@PathParam("id") String id) {
      logger.debugf("Getting GatewayGroup with id %s", id);
      GatewayGroup gatewayGroup = gatewayGroupRepository.findById(id);
      if (gatewayGroup == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return  Response.ok(v1Mappers.toResource(gatewayGroup)).build();
   }

   @POST
   @Authenticated
   @Transactional
   @QuotaRestricted(metric = QuotaMetric.GATEWAY_GROUP_COUNT)
   @Produces(MediaType.APPLICATION_JSON)
   public Response createGatewayGroup(@Valid GatewayGroupDTO gatewayGroupDTO) {
      logger.debugf("Creating new GatewayGroup with name %s", gatewayGroupDTO.name());

      if (securityIdentity == null) {
         logger.warn("Security identity is not available. Cannot create GatewayGroup.");
         return Response.status(Response.Status.UNAUTHORIZED)
               .entity("Security identity is not available. Please authenticate.").build();
      }

      GatewayGroup gatewayGroup = v1Mappers.fromResource(gatewayGroupDTO);
      gatewayGroup.organizationId = securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY);

      gatewayGroupRepository.persistAndFlush(gatewayGroup);
      return Response.status(Response.Status.CREATED)
            .entity(v1Mappers.toResource(gatewayGroup)).build();
   }

   @PUT
   @Authenticated
   @Transactional
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response updateGatewayGroup(@PathParam("id") String id,  @Valid GatewayGroupDTO gatewayGroupDTO) {
      logger.debugf("Updating GatewayGroup with id %s", id);
      GatewayGroup gatewayGroup = gatewayGroupRepository.findById(id);
      if (gatewayGroup == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      // Map new values from DTO - we cannot use MapStruct here because we'd have a detached entity.
      // We need to preserve id and organizationId.
      gatewayGroup.name = gatewayGroupDTO.name();
      gatewayGroup.labels = gatewayGroupDTO.labels();

      gatewayGroupRepository.persist(gatewayGroup);
      return Response.ok(v1Mappers.toResource(gatewayGroup)).build();
   }

   @DELETE
   @Authenticated
   @Transactional
   @ReleaseQuota(metric = QuotaMetric.GATEWAY_GROUP_COUNT)
   @Path("/{id}")
   public Response deleteGatewayGroup(@PathParam("id") String id) {
      logger.debugf("Deleting GatewayGroup with id %s", id);
      GatewayGroup gatewayGroup = gatewayGroupRepository.findById(id);
      if (gatewayGroup == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      gatewayGroupRepository.delete(gatewayGroup);
      return Response.noContent().build();
   }
}
