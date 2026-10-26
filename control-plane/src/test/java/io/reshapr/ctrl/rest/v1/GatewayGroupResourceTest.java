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

import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.QuotaMetric;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link GatewayGroupResource}.
 * Verifies real HTTP handling, JWT authentication, tenant isolation,
 * PostgreSQL persistence, and automatic quota enforcement/release.
 *
 * @author vaishnav
 */
@QuarkusTest
@TestProfile(GatewayGroupResourceTest.RandomPostgresPortProfile.class)
@ActivateRequestContext
class GatewayGroupResourceTest {

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

      // Seed quota for GATEWAY_GROUP_COUNT so that @QuotaRestricted endpoints can be invoked
      QuarkusTransaction.requiringNew().run(() -> {
         Quota quota = new Quota();
         quota.organizationId = currentTenant;
         quota.metric = QuotaMetric.GATEWAY_GROUP_COUNT.toString();
         quota.enabled = true;
         quota.limit = 10L;
         quota.remaining = 10L;
         quota.persist();
      });
   }

   /**
    * Verifies the complete CRUD lifecycle of a GatewayGroup:
    * creation with quota decrement, fetching by ID, listing, updating,
    * deletion with quota release, and subsequent 404 confirmation.
    */
   @Test
   void testCreateGetUpdateDeleteGatewayGroupLifecycle() {
      GatewayGroupDTO createRequest = new GatewayGroupDTO(null, null, "Test Gateway Group", Map.of("env", "test"));

      // 1. Create (POST)
      String id = given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.JSON)
            .body(createRequest)
            .when()
            .post("/api/v1/gatewayGroups")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("name", equalTo("Test Gateway Group"))
            .body("organizationId", equalTo(currentTenant))
            .body("labels.env", equalTo("test"))
            .extract()
            .path("id");

      // Verify quota was decremented on creation by the @QuotaRestricted interceptor
      QuarkusTransaction.requiringNew().run(() -> {
         Quota quota = Quota.getByMetricAndOrganization(QuotaMetric.GATEWAY_GROUP_COUNT.toString(), currentTenant);
         assertEquals(9L, quota.remaining);
      });

      // 2. Read by ID (GET)
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gatewayGroups/{id}", id)
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("name", equalTo("Test Gateway Group"))
            .body("organizationId", equalTo(currentTenant))
            .body("labels.env", equalTo("test"));

      // 3. List (GET)
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gatewayGroups")
            .then()
            .statusCode(200)
            .body("id", hasItem(id))
            .body("name", hasItem("Test Gateway Group"));

      // 4. Update (PUT)
      GatewayGroupDTO updateRequest = new GatewayGroupDTO(id, currentTenant, "Updated Gateway Group", Map.of("env", "prod", "region", "eu"));
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.JSON)
            .body(updateRequest)
            .when()
            .put("/api/v1/gatewayGroups/{id}", id)
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("name", equalTo("Updated Gateway Group"))
            .body("labels.env", equalTo("prod"))
            .body("labels.region", equalTo("eu"));

      // Verify update persisted in database
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gatewayGroups/{id}", id)
            .then()
            .statusCode(200)
            .body("name", equalTo("Updated Gateway Group"))
            .body("labels.region", equalTo("eu"));

      // 5. Delete (DELETE)
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .delete("/api/v1/gatewayGroups/{id}", id)
            .then()
            .statusCode(204);

      // Verify quota was released back to 10 by the @ReleaseQuota interceptor
      QuarkusTransaction.requiringNew().run(() -> {
         Quota quota = Quota.getByMetricAndOrganization(QuotaMetric.GATEWAY_GROUP_COUNT.toString(), currentTenant);
         assertEquals(10L, quota.remaining);
      });

      // 6. Verify GET returns 404 after deletion
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gatewayGroups/{id}", id)
            .then()
            .statusCode(404);
   }

   /**
    * Verifies that requesting a non-existent gateway group ID returns 404 Not Found.
    */
   @Test
   void testGetGatewayGroupNotFound() {
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gatewayGroups/{id}", "non-existent-id")
            .then()
            .statusCode(404);
   }

   /**
    * Verifies that attempting to update a non-existent gateway group returns 404 Not Found.
    */
   @Test
   void testUpdateGatewayGroupNotFound() {
      GatewayGroupDTO updateRequest = new GatewayGroupDTO("non-existent-id", currentTenant, "Updated", null);
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.JSON)
            .body(updateRequest)
            .when()
            .put("/api/v1/gatewayGroups/{id}", "non-existent-id")
            .then()
            .statusCode(404);
   }

   /**
    * Verifies that attempting to delete a non-existent gateway group returns 404 Not Found.
    */
   @Test
   void testDeleteGatewayGroupNotFound() {
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .delete("/api/v1/gatewayGroups/{id}", "non-existent-id")
            .then()
            .statusCode(404);
   }

   /**
    * Verifies that requests without a valid JWT bearer token are rejected with 401 Unauthorized.
    */
   @Test
   void testUnauthenticatedAccessReturns401() {
      given()
            .when()
            .get("/api/v1/gatewayGroups")
            .then()
            .statusCode(401);

      GatewayGroupDTO createRequest = new GatewayGroupDTO(null, null, "No Auth", null);
      given()
            .contentType(ContentType.JSON)
            .body(createRequest)
            .when()
            .post("/api/v1/gatewayGroups")
            .then()
            .statusCode(401);
   }

   public static class RandomPostgresPortProfile implements QuarkusTestProfile {
      @Override
      public Map<String, String> getConfigOverrides() {
         return Map.of("quarkus.datasource.devservices.port", "0");
      }
   }
}
