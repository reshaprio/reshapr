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
package io.reshapr.ctrl.security;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.tenant.TenantResolver;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.RequestScoped;
import org.jboss.logging.Logger;

/**
 * ReshaprTenantResolver is a custom tenant resolver for the Reshapr application.
 * It resolves the tenant ID based on the current routing context and tenant context.
 */
@RequestScoped
@PersistenceUnitExtension
public class ReshaprTenantResolver implements TenantResolver {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   public static final String ROOT_TENANT_ID = "reshapr";
   public static final String DEFAULT_TENANT_ID = "default";

   public static final String TENANT_ID_CONTEXT_KEY = "organizationId";

   private final CurrentVertxRequest vertxRequest;

   public ReshaprTenantResolver(CurrentVertxRequest vertxRequest) {
      this.vertxRequest = vertxRequest;
   }

   @Override
   public String getDefaultTenantId() {
      return DEFAULT_TENANT_ID;
   }

   @Override
   public String resolveTenantId() {
      logger.tracef("resolveTenantId() called with routing context from request: %s", vertxRequest.getCurrent());

      String organizationId = null;
      if (Vertx.currentContext() != null) {
         organizationId = Vertx.currentContext().getLocal(TENANT_ID_CONTEXT_KEY);
         logger.tracef("resolveTenantId() organizationId from Vertx currentContext: %s", organizationId);
      }
      if (organizationId != null) {
         return organizationId;
      }

      organizationId = ReshaprTenantContext.getCurrentTenant();
      logger.tracef("resolveTenantId() organizationId from tenant context: %s", organizationId);
      if (organizationId != null) {
         return organizationId;
      }
      return DEFAULT_TENANT_ID;
   }

   @Override
   public boolean isRoot(String tenantId) {
      logger.tracef("isRoot() called with tenantId: %s", tenantId);
      return ROOT_TENANT_ID.equals(tenantId);
   }
}
