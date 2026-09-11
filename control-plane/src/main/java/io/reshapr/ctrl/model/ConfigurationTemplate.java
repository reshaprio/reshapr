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
package io.reshapr.ctrl.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Type;

/**
 * A reusable configuration template that can be used to pre-populate a {@link ConfigurationPlan}.
 * A template captures common, organisation-wide settings (e.g. OAuth2 configuration) so that
 * users can quickly initialise a plan and then tune it individually without affecting other plans.
 *
 * @author vaishnav
 */
@Entity
@Table(name = "configuration_templates", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"organization_id", "name"})
})
public class ConfigurationTemplate extends TenantAwareEntity {

   @Column(nullable = false)
   public String name;

   public String description;

   /**
    * Optional reusable OAuth2 configuration that will be copied into a {@link ConfigurationPlan}
    * when the template is applied. The copy is independent; subsequent changes to this template
    * do NOT affect plans that have already been initialised from it.
    */
   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "oauth2_configuration")
   public ConfigurationPlan.OAuth2Configuration oauth2Configuration;
}
