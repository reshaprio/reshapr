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
package io.reshapr.ctrl.repository;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.reshapr.ctrl.model.Exposition;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Repository for managing expositions in the Reshapr control plane.
 * @author laurent
 */
@ApplicationScoped
public class ExpositionRepository implements PanacheRepositoryBase<Exposition, String> {

   public List<Exposition> findByServiceId(String serviceId) {
      return find("from Exposition e left join fetch e.service " +
            "left join fetch e.configurationPlan where e.service.id = ?1", serviceId).list();
   }

   public List<Exposition> findByConfigurationPlanId(String configurationPlanId) {
      return find("from Exposition e where e.configurationPlan.id = ?1", configurationPlanId).list();
   }

   public List<Exposition> findByGatewayGroupId(String gatewayGroupId) {
      return find("from Exposition e left join fetch e.service " +
            "left join fetch e.configurationPlan where gatewayGroup.id = ?1", gatewayGroupId).list();
   }

   public PanacheQuery<Exposition> loadById(String expositionId) {
      return find("from Exposition e left join fetch e.service " +
            "left join fetch e.configurationPlan where e.id = ?1", expositionId);
   }

   /**
    * Finds an exposition by its name within the current organization (the tenant discriminator is applied
    * automatically). Used to enforce the organization-unique exposition name.
    * @param name The exposition name to look up.
    * @return The matching exposition, or null if none.
    */
   public Exposition findByName(String name) {
      return find("from Exposition e where e.name = ?1", name).firstResult();
   }

   /**
    * Finds an exposition matching the given service, gateway group and configuration plan triple within the
    * current organization (the tenant discriminator is applied automatically). Used to enforce the unique
    * constraint that forbids exposing the same configuration plan on the same gateway group twice.
    * @param serviceId The service identifier.
    * @param gatewayGroupId The gateway group identifier.
    * @param configurationPlanId The configuration plan identifier.
    * @return The matching exposition, or null if none.
    */
   public Exposition findByServiceAndGatewayGroupAndConfigurationPlan(String serviceId, String gatewayGroupId,
                                                                      String configurationPlanId) {
      return find("from Exposition e where e.service.id = ?1 and e.gatewayGroup.id = ?2 " +
            "and e.configurationPlan.id = ?3", serviceId, gatewayGroupId, configurationPlanId).firstResult();
   }
}
