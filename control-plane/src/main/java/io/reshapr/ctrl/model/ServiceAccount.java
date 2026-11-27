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
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * A Service Account identity that is allowed to impersonate user &amp; organization for to access Reshapr
 * control plane REST APIs.
 * @author laurent
 */
@Entity
@Table(name = "service_accounts")
public class ServiceAccount extends BaseEntity {

   /** Unique name of the service account in Reshapr */
   @Column(nullable = false, unique = true)
   public String name;

   public String description;

   /** If a k8s SA, this is the subject in form: "namespace:sa-name" */
   @Column(name = "k8s_subject", unique = true)
   public String k8sSubject;

   @Column(name="valid_until", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
   public OffsetDateTime validUntil;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "allowed_organizations")
   public List<String> allowedOrganizations;

   public boolean isValid() {
      return validUntil != null && validUntil.isAfter(OffsetDateTime.now(ZoneId.systemDefault()));
   }
}
