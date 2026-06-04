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

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.List;

/**
 * A user of the Reshapr control plane.
 * @author laurent
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

   @Column(nullable = false)
   public String email;

   @Column(unique = true)
   public String username;
   public String firstname;
   public String lastname;
   public String password; // This is hashed if Reshapr is configured to use password authentication.

   @Column(nullable = false)
   @Enumerated(EnumType.STRING)
   public UserStatus status;

   @ManyToOne
   @JoinColumn(name = "default_organization_id")
   public Organization defaultOrganization;

   @ManyToMany
   public List<Organization> organizations;


   public boolean isRegistered(){
      return status == UserStatus.REGISTERED;
   }

   public String getUserId() {
      return username;
   }

   public boolean verifyPassword(String challenged) {
      return password != null && BcryptUtil.matches(challenged, password);
   }

   /**
    * Ensure the user has a default organization when they belong to at least one.
    * Called whenever a membership is added or reassigned.
    */
   public void ensureDefaultOrganization() {
      if (defaultOrganization != null) {
         return;
      }
      if (organizations != null && !organizations.isEmpty()) {
         defaultOrganization = organizations.getFirst();
      }
   }
}
