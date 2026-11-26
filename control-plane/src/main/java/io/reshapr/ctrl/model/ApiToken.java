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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * An API token that can be used by gateways to access Reshapr control plane gRPC APIs.
 * @author laurent
 */
@Entity
@Table(name = "api_tokens", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"organization_id", "name"})})
public class ApiToken extends BaseEntity {

   @Column(nullable = false)
   public String name;

   @Column(nullable = false)
   public String token;

   @Column(name="organization_id", nullable = false)
   public String organizationId;

   @Column(name = "valid_until", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
   public OffsetDateTime validUntil;

   @ManyToOne(fetch = FetchType.EAGER)
   public User user;

   public boolean isValid() {
      return validUntil != null && validUntil.isAfter(OffsetDateTime.now(ZoneId.systemDefault()));
   }
}
