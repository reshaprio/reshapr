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

import io.reshapr.ctrl.model.Gateway;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Integration tests for {@link GatewayResource}.
 * Verifies active gateway listing, JSON mapping, multi-tenant isolation,
 * and security authentication against a running PostgreSQL database.
 *
 * @author vaishnav
 */
@QuarkusTest
@TestProfile(GatewayResourceTest.RandomPostgresPortProfile.class)
@ActivateRequestContext
class GatewayResourceTest {

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

   /**
    * Verifies that GET /api/v1/gateways returns an empty JSON array when no active
    * gateways are registered for the tenant.
    */
   @Test
   void testListGatewaysEmptyWhenNoneActive() {
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gateways")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("$", empty());
   }

   /**
    * Verifies that GET /api/v1/gateways returns active gateways registered for the tenant,
    * with correct JSON serialization of fields (version, fqdns, labels).
    */
   @Test
   void testListGatewaysReturnsActiveGatewaysForTenant() {
      // Seed a gateway for currentTenant
      QuarkusTransaction.requiringNew().run(() -> {
         Gateway gateway = new Gateway();
         gateway.name = "edge-gw-" + UUID.randomUUID();
         gateway.version = "1.0.0";
         gateway.organizationId = currentTenant;
         gateway.startedAt = OffsetDateTime.now();
         gateway.lastHeartbeat = OffsetDateTime.now();
         gateway.fqdns = List.of("edge.example.com");
         gateway.labels = Map.of("region", "eu-west-1", "tier", "edge");
         gateway.persist();
      });

      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gateways")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("$", hasSize(1))
            .body("[0].version", equalTo("1.0.0"))
            .body("[0].fqdns", equalTo(List.of("edge.example.com")))
            .body("[0].labels.region", equalTo("eu-west-1"));
   }

   /**
    * Verifies strict tenant isolation: gateways belonging to a different tenant
    * must never be returned in the current tenant's query.
    */
   @Test
   void testListGatewaysTenantIsolation() {
      String otherTenant = "other-org-" + UUID.randomUUID();

      // Seed a gateway belonging to a different tenant
      ReshaprTenantContext.setCurrentTenant(otherTenant);
      QuarkusTransaction.requiringNew().run(() -> {
         Gateway gateway = new Gateway();
         gateway.name = "other-gw-" + UUID.randomUUID();
         gateway.version = "2.0.0";
         gateway.organizationId = otherTenant;
         gateway.startedAt = OffsetDateTime.now();
         gateway.lastHeartbeat = OffsetDateTime.now();
         gateway.persist();
      });

      // Switch back to currentTenant and query with currentTenant's JWT
      ReshaprTenantContext.setCurrentTenant(currentTenant);
      given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/gateways")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("$", empty());
   }

   /**
    * Verifies that requests without a valid JWT bearer token are rejected with 401 Unauthorized.
    */
   @Test
   void testListGatewaysUnauthenticatedReturns401() {
      given()
            .when()
            .get("/api/v1/gateways")
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
