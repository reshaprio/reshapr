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

import io.reshapr.ctrl.control.QuotaExceededException;
import io.reshapr.ctrl.model.Gateway;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.QuotaMetric;
import io.reshapr.ctrl.repository.GatewayRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.grpc.GrpcService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class GatewayManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final GatewayRepository gatewayRepository;
   private final ExpositionDiscoveryServiceHandler expositionDiscoveryServiceHandler;

   /**
    * Constructor for GatewayManagerService.
    * @param gatewayRepository the repository to manage gateways
    */
   public GatewayManagerService(GatewayRepository gatewayRepository,
                                @GrpcService ExpositionDiscoveryServiceHandler expositionDiscoveryServiceHandler) {
      this.gatewayRepository = gatewayRepository;
      this.expositionDiscoveryServiceHandler = expositionDiscoveryServiceHandler;
   }

   /**
    * Retrieve the list of currently active gateways for the current tenant. Gateways are considered
    * active as long as they keep reporting health: expired registrations are pruned by the
    * {@code GatewayRegistrationCleaner}. The multi-tenant filter is applied automatically as
    * {@link Gateway} is a tenant-aware entity.
    * @return the list of active gateways owned by the current tenant
    */
   public List<Gateway> getActiveGateways() {
      logger.debug("Retrieving active gateways for the current tenant");
      return gatewayRepository.listAllWithGroups();
   }

   @Transactional
   public void registerGateway(String gatewayName, List<GatewayGroup> matchingGroups, List<String> fqdns,
                               Map<String, String> labels, String version) throws QuotaExceededException {
      logger.infof("Registering gateway with name: '%s'", gatewayName);

      Optional<Gateway> gatewayOpt = gatewayRepository.findByName(gatewayName);
      boolean isNewGateway = gatewayOpt.isEmpty();

      // A gateway only consumes quota when it is registered for the first time. Subsequent heartbeats
      // simply refresh the existing registration and must not decrement the quota again. Enforcement
      // happens up-front, before anything is persisted, so a rejected registration fails fast and leaves
      // no partial state behind. The owning organization is taken from the tenant context, which is set
      // at authentication time.
      if (isNewGateway) {
         consumeGatewayCountQuota(ReshaprTenantContext.getCurrentTenant());
      }

      Gateway gateway = gatewayOpt.orElseGet(() -> {
         logger.infof("Gateway with ID %s not found, creating a new one", gatewayName);
         Gateway newGateway = new Gateway();
         newGateway.name = gatewayName;
         newGateway.startedAt = OffsetDateTime.now(ZoneId.systemDefault());
         return newGateway;
      });

      gateway.lastHeartbeat = OffsetDateTime.now(ZoneId.systemDefault());
      gateway.fqdns = fqdns;
      gateway.labels = labels;
      gateway.version = version;
      gateway.gatewayGroups = matchingGroups;
      gatewayRepository.persist(gateway);
   }

   @Transactional
   public boolean updateGatewayHeartbeat(String gatewayName) {
      logger.infof("Updating heartbeat for gateway with name: '%s'", gatewayName);

      Optional<Gateway> gatewayOpt = gatewayRepository.findByName(gatewayName);
      if (gatewayOpt.isEmpty()) {
         logger.warnf("Gateway with ID %s not found", gatewayName);
         return false;
      }

      Gateway gateway = gatewayOpt.get();
      gateway.lastHeartbeat = OffsetDateTime.now(ZoneId.systemDefault());
      gatewayRepository.persist(gateway);
      return true;
   }

   @Transactional
   public void unregisterGateway(String gatewayName) {
      logger.infof("Unregistering gateway with name: '%s'", gatewayName);

      Optional<Gateway> gatewayOpt = gatewayRepository.findByName(gatewayName);
      if (gatewayOpt.isEmpty()) {
         logger.warnf("Gateway with ID %s not found", gatewayName);
         return;
      }
      Gateway gateway = gatewayOpt.get();
      String organizationId = gateway.organizationId;
      // Clear exposition observers for this gateway to avoid trying to send updates to a non-existing gateway.
      expositionDiscoveryServiceHandler.clearObserver(organizationId, gatewayName);
      gateway.delete();

      // Releasing the gateway back to the organization's quota now that it is effectively gone.
      releaseGatewayCountQuota(organizationId);
   }

   @Transactional
   public void cleanExpiredRegistrations(OffsetDateTime beforeDate) {
      List<Gateway> gateways = gatewayRepository.findAllWithHeartbeatBefore(beforeDate);
      if (!gateways.isEmpty()) {
         logger.infof("Cleaning %d expired gateway registrations", gateways.size());
      }
      // This runs under the root tenant and may span several organizations, so the quota is always
      // released for each gateway's own organization rather than for the current tenant context.
      for (Gateway gateway : gateways) {
         String organizationId = gateway.organizationId;
         gateway.delete();
         releaseGatewayCountQuota(organizationId);
      }
   }

   /**
    * Enforce and consume one unit of the {@link QuotaMetric#GATEWAY_COUNT} quota for the given
    * organization. When the quota exists and is enabled, a {@link QuotaExceededException} is thrown if
    * no unit remains; it is up to the calling layer to translate that business exception into the
    * relevant protocol error. As it is invoked before the gateway is persisted, a rejection leaves no
    * partial state behind. When no quota row exists or it is disabled, the gateway count is left
    * untracked, i.e. unlimited.
    * @param organizationId the organization owning the gateway being registered
    * @throws QuotaExceededException if the organization has no remaining gateway quota
    */
   private void consumeGatewayCountQuota(String organizationId) throws QuotaExceededException {
      Quota quota = Quota.getByMetricAndOrganization(QuotaMetric.GATEWAY_COUNT.toString(), organizationId);
      if (quota == null || !quota.enabled) {
         return;
      }
      if (quota.remaining <= 0) {
         logger.warnf("Gateway quota limit reached for organization %s", organizationId);
         throw new QuotaExceededException(QuotaMetric.GATEWAY_COUNT.toString(), organizationId);
      }
      int updated = Quota.decrementRemaining(QuotaMetric.GATEWAY_COUNT.toString(), organizationId);
      logger.debugf("Decremented gateway quota for organization %s (rows updated: %d)", organizationId, updated);
   }

   /**
    * Release one unit of the {@link QuotaMetric#GATEWAY_COUNT} quota for the given organization. The
    * underlying increment is a no-op when the quota is absent or disabled, and is capped at the quota
    * limit so releases can never exceed the configured maximum.
    * @param organizationId the organization owning the gateway being unregistered
    */
   private void releaseGatewayCountQuota(String organizationId) {
      int updated = Quota.incrementRemaining(QuotaMetric.GATEWAY_COUNT.toString(), organizationId);
      logger.debugf("Released gateway quota for organization %s (result: %d)", organizationId, updated);
   }
}
