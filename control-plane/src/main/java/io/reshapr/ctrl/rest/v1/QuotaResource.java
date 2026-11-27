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

import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.security.ReshaprTenantResolver;

import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/api/v1/quotas")
public class QuotaResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final Mappers v1Mappers;
   private final SecurityIdentity securityIdentity;

   public QuotaResource(Mappers v1Mappers, SecurityIdentity securityIdentity) {
      this.v1Mappers = v1Mappers;
      this.securityIdentity = securityIdentity;
   }

   @GET
   @Authenticated
   @Produces(MediaType.APPLICATION_JSON)
   public Response getQuotas() {
      logger.debug("Retrieving all quotas");

      if (securityIdentity == null) {
         logger.warn("Security identity is not available. Cannot create GatewayGroup.");
         return Response.status(Response.Status.UNAUTHORIZED)
               .entity("Security identity is not available. Please authenticate.").build();
      }

      String organizationId = securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY);
      List<Quota> quotas = Quota.list("organizationId", Sort.ascending("metric"), organizationId);

      return Response.ok().entity(v1Mappers.toResources(quotas)).build();
   }
}
