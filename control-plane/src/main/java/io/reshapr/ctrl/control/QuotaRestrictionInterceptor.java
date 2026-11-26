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
package io.reshapr.ctrl.control;

import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.QuotaMetric;
import io.reshapr.ctrl.security.ReshaprTenantResolver;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * An abstract interceptor to enforce and increment quota on successful method completion.
 * @author laurent
 */
public abstract class QuotaRestrictionInterceptor {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final SecurityIdentity securityIdentity;
   private final QuotaMetric metric;

   protected QuotaRestrictionInterceptor(QuotaMetric metric, SecurityIdentity securityIdentity) {
      this.metric = metric;
      this.securityIdentity = securityIdentity;
   }

   @AroundInvoke
   Object decreaseQuotaOnSuccess(InvocationContext context) throws Exception {
      logger.debugf("Processing request for %s", context.getMethod());

      // Ensure we have a security identity first.
      if (securityIdentity == null) {
         logger.warn("Security identity is not available. Cannot enforce Quota on resource creation.");
         throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
               .entity("Security identity is not available. Please authenticate.")
               .build());
      }

      String organizationId = securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY);

      logger.debugf("Checking quota on metric '%s' for org '%s'", metric, organizationId);

      Quota quota = Quota.getByMetricAndOrganization(metric.toString(), organizationId);
      logger.debugf("Current quota for metric %s: %s", metric, quota);
      if (quota == null || (quota.enabled && quota.remaining <= 0)) {
         // Reject the request if the quota limit is reached
         logger.warnf("Quota limit reached for metric: %s on organization %s", metric, organizationId);
         throw new ClientErrorException(Response.status(Response.Status.TOO_MANY_REQUESTS)
               .entity("Quota limit reached for metric: " + metric)
               .build());
      }

      // Proceed with the method invocation.
      Object result;
      try {
         result = context.proceed();
      } catch (Exception e) {
         logger.debugf("Method invocation failed: %s", e.getMessage());
         throw e; // Rethrow the exception to maintain original behavior
      }

      // Only decrease the quota if the method invocation was successful.
      logger.debugf("Updating quota on metric '%s' for org '%s'", metric, organizationId);
      int updatedQuotas = Quota.decrementRemaining(metric.toString(), organizationId);
      logger.debugf("Updated remaining quota for metric %s: %d", metric, updatedQuotas);

      return result;
   }
}
