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

import io.reshapr.ctrl.security.CipheredAttributeConverter;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Type;

/**
 * A secret that can be used by control plane to access Reshapr remote resources to be imported.
 * This can include credentials, tokens, or certificates.
 * @author laurent
 */
@Entity
@Table(name = "secrets", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"organization_id", "name"})})
public class Secret extends TenantAwareEntity {

   @Column(nullable = false)
   public String name;
   public String description;

   @Enumerated(EnumType.STRING)
   public SecretType type;

   @Enumerated(EnumType.STRING)
   @Column(name = "auth_method")
   public SecretAuthMethod authMethod;

   public String username;

   @Convert(converter = CipheredAttributeConverter.class)
   private String password;

   @Convert(converter = CipheredAttributeConverter.class)
   @Column(columnDefinition = "TEXT")
   private String token;
   public String tokenHeader;

   @Column(columnDefinition = "TEXT")
   public String certPem;

   @Column(name = "use_elicitation", nullable = false)
   public boolean useElicitation = false;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "oauth2_client_configuration")
   public OAuth2ClientConfiguration oauth2ClientConfiguration;

   public String getPassword() {
      return password;
   }

   public void setPassword(String password) {
      this.password = password;
   }

   public String getToken() {
      return token;
   }

   public void setToken(String token) {
      this.token = token;
   }

   public record OAuth2ClientConfiguration(
         String clientId,
         String clientSecret,
         String authorizationEndpoint,
         String tokenEndpoint,
         java.util.List<String> scopes) {
   }
}
