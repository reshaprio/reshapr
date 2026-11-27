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

import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.repository.ApiTokenRepository;
import io.reshapr.ctrl.security.ReshaprTenantResolver;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.TokenManagerService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/api/v1/tokens")
public class TokenResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final TokenManagerService tokenManagerService;
   private final ApiTokenRepository apiTokenRepository;
   private final Mappers v1Mappers;
   private final SecurityIdentity securityIdentity;

   public TokenResource(TokenManagerService tokenManagerService, ApiTokenRepository apiTokenRepository,
                        Mappers v1Mappers, SecurityIdentity securityIdentity) {
      this.tokenManagerService = tokenManagerService;
      this.apiTokenRepository = apiTokenRepository;
      this.v1Mappers = v1Mappers;
      this.securityIdentity = securityIdentity;
   }

   @GET
   @Authenticated
   @Path("/apiTokens")
   @Produces(MediaType.APPLICATION_JSON)
   public List<ApiTokenDTO> getApiTokens() {
      // Extract security elements from the security identity.
      String organizationId = securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY);
      logger.debugf("Retrieving API tokens for organization %s", organizationId);
      return v1Mappers.toATResources(tokenManagerService.getApiTokens(organizationId));
   }

   @POST
   @Authenticated
   @Path("/apiTokens")
   @Produces(MediaType.APPLICATION_JSON)
   public Response createApiToken(ApiTokenRequestDTO apiTokenRequestDTO) {
      logger.debugf("Creating new API token with name %s", apiTokenRequestDTO.name());

      // Extract security elements from the security identity.
      String username = securityIdentity.getPrincipal().getName();
      String organizationId = securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY);
      logger.debugf("Creating API token by user %s in organization %s", username, organizationId);

      ApiToken apiToken = null;
      try {
         apiToken = tokenManagerService.generateApiToken(apiTokenRequestDTO.name(), organizationId, apiTokenRequestDTO.validityDays().getDays(), username);
      } catch (DependencyNotFoundException e) {
         logger.error("Failed to create API token due to missing dependency", e);
         return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
      }

      // On creation, be sure to show the token value only once. It will not be shown again after this.
      ApiTokenDTO result = v1Mappers.toResource(apiToken);
      result.setToken(apiToken.token);
      return Response.status(Response.Status.CREATED).entity(result).build();
   }

   @DELETE
   @Authenticated
   @Path("/apiTokens/{tokenId}")
   public Response deleteApiToken(@PathParam("tokenId") String tokenId) {
      logger.debugf("Deleting API token with id %s", tokenId);
      ApiToken apiToken = apiTokenRepository.findById(tokenId);
      if (apiToken == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      tokenManagerService.revokeApiToken(apiToken);
      return Response.noContent().build();
   }
}
