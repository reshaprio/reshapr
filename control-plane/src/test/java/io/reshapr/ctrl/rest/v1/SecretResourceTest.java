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

import io.reshapr.ctrl.model.SecretAuthMethod;
import io.reshapr.ctrl.model.SecretType;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.control.ActivateRequestContext;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static io.restassured.RestAssured.given;

/**
 * Integration tests for {@link SecretResource}.
 * @author laurent
 */
@QuarkusTest
@TestProfile(SecretResourceTest.RandomPostgresPortProfile.class)
@ActivateRequestContext
class SecretResourceTest {

   private String currentTenant;
   private String bearerToken;

   @BeforeEach
   void setup() {
      currentTenant = "test-org-" + UUID.randomUUID();
      ReshaprTenantContext.setCurrentTenant(currentTenant);
      bearerToken = Jwt.issuer("https://app.reshapr.io")
            .subject("test-user")
            .upn("test-user")
            .groups("user")
            .expiresIn(Duration.ofMinutes(5))
            .claim("org", currentTenant)
            .sign();
   }

   @Test
   void createSecretPersistsClientCredentialsAuthMethodAndScopes() {
      SecretDTO createRequest = secretDto(null, "client-credentials-secret-" + UUID.randomUUID(),
            SecretAuthMethod.OAUTH2_CLIENT_CREDENTIALS, "client-id", "client-secret",
            "https://issuer.example.com/token", List.of("inventory.read", "orders.write"));

      String id = given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.JSON)
            .body(createRequest)
            .when()
            .post("/api/v1/secrets")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
      assertNotNull(id);

      JsonPath reloaded = given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/secrets/{id}", id)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
      assertEquals(SecretAuthMethod.OAUTH2_CLIENT_CREDENTIALS.name(), reloaded.getString("authMethod"));
      assertFalse(reloaded.getBoolean("useElicitation"));
      assertNotNull(reloaded.getMap("oauth2ClientConfiguration"));
      assertEquals("client-id", reloaded.getString("oauth2ClientConfiguration.clientId"));
      assertEquals("https://issuer.example.com/token", reloaded.getString("oauth2ClientConfiguration.tokenEndpoint"));
      assertEquals(List.of("inventory.read", "orders.write"),
            reloaded.getList("oauth2ClientConfiguration.scopes", String.class));
   }

   @Test
   void updateSecretPersistsClientCredentialsAuthMethodAndOauth2Configuration() {
      String secretName = "updatable-secret-" + UUID.randomUUID();
      String id = given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.JSON)
            .body(secretDto(null, secretName, SecretAuthMethod.BASIC, null, null, null, null))
            .when()
            .post("/api/v1/secrets")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

      SecretDTO updateRequest = secretDto(id, secretName,
            SecretAuthMethod.OAUTH2_CLIENT_CREDENTIALS, "updated-client-id", "updated-client-secret",
            "https://issuer.example.com/oauth/token", List.of("payments.read"));

      given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.JSON)
            .body(updateRequest)
            .when()
            .put("/api/v1/secrets/{id}", id)
            .then()
            .statusCode(200);

      JsonPath reloaded = given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/secrets/{id}", id)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
      assertEquals(SecretAuthMethod.OAUTH2_CLIENT_CREDENTIALS.name(), reloaded.getString("authMethod"));
      assertFalse(reloaded.getBoolean("useElicitation"));
      assertNotNull(reloaded.getMap("oauth2ClientConfiguration"));
      assertEquals("updated-client-id", reloaded.getString("oauth2ClientConfiguration.clientId"));
      assertEquals("https://issuer.example.com/oauth/token",
            reloaded.getString("oauth2ClientConfiguration.tokenEndpoint"));
      assertEquals(List.of("payments.read"), reloaded.getList("oauth2ClientConfiguration.scopes", String.class));
   }

   private SecretDTO secretDto(String id, String name, SecretAuthMethod authMethod, String clientId,
         String clientSecret, String tokenEndpoint, List<String> scopes) {
      OAuth2ClientConfigurationDTO oauth2ClientConfiguration = clientId == null && clientSecret == null
            && tokenEndpoint == null && scopes == null
            ? null
            : new OAuth2ClientConfigurationDTO(clientId, clientSecret, null, tokenEndpoint, scopes);
      return new SecretDTO(id, currentTenant, name, "test secret", SecretType.ENDPOINT, authMethod,
            null, null, null, null, null, false, oauth2ClientConfiguration);
   }

   public static class RandomPostgresPortProfile implements QuarkusTestProfile {
      @Override
      public Map<String, String> getConfigOverrides() {
         return Map.of("quarkus.datasource.devservices.port", "0");
      }
   }
}
