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
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests verifying constructor-injected {@code SecurityIdentity} and quota
 * restriction/release interceptors across {@link GatewayGroupResource}, {@link QuotaResource},
 * and {@link TokenResource}.
 * @author vaishnav
 */
@QuarkusTest
@TestProfile(SecretResourceTest.RandomPostgresPortProfile.class)
@ActivateRequestContext
class GatewayGroupResourceTest {

   /** Signs a short-lived JWT carrying the given organization id as the {@code org} claim. */
   private String signToken(String tenantId) {
      return Jwt.issuer("https://app.reshapr.io")
            .subject("test-user-" + tenantId)
            .upn("test-user-" + tenantId)
            .groups("user")
            .expiresIn(Duration.ofMinutes(5))
            .claim("org", tenantId)
            .sign();
   }

   /** Seeds an enabled {@code GATEWAY_GROUP_COUNT} quota for the given tenant in a new transaction. */
   private void seedGatewayGroupQuota(String tenantId, long limit) {
      ReshaprTenantContext.setCurrentTenant(tenantId);
      QuarkusTransaction.requiringNew().run(() -> {
         Quota quota = new Quota();
         quota.organizationId = tenantId;
         quota.metric = QuotaMetric.GATEWAY_GROUP_COUNT.toString();
         quota.enabled = true;
         quota.limit = limit;
         quota.remaining = limit;
         quota.persist();
      });
   }

   /** Fetches the remaining {@code GATEWAY_GROUP_COUNT} quota via {@link QuotaResource}. */
   private long fetchRemainingGatewayGroupQuota(String bearerToken) {
      JsonPath json = given()
            .header("Authorization", "Bearer " + bearerToken)
            .when()
            .get("/api/v1/quotas")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
      List<Map<String, Object>> quotas = json.getList("$");
      assertEquals(1, quotas.size());
      assertEquals(QuotaMetric.GATEWAY_GROUP_COUNT.toString(), quotas.getFirst().get("metric"));
      return ((Number) quotas.getFirst().get("remaining")).longValue();
   }

   @Test
   void createAndDeleteGatewayGroupEnforcesAndReleasesQuota() {
      String tenantA = "test-org-" + UUID.randomUUID();
      String tenantB = "test-org-" + UUID.randomUUID();
      seedGatewayGroupQuota(tenantA, 1L);
      seedGatewayGroupQuota(tenantB, 2L);

      String tokenA = signToken(tenantA);
      String tokenB = signToken(tenantB);

      // Verify QuotaResource resolves the current request's SecurityIdentity across different tenants.
      assertEquals(1L, fetchRemainingGatewayGroupQuota(tokenA));
      assertEquals(2L, fetchRemainingGatewayGroupQuota(tokenB));

      // Create a gateway group for tenant A: @QuotaRestricted decrements remaining quota from 1 to 0.
      JsonPath created = given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body(new GatewayGroupDTO(null, null, "gg-alpha", Map.of("env", "test")))
            .when()
            .post("/api/v1/gatewayGroups")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
      String gatewayGroupId = created.getString("id");
      assertNotNull(gatewayGroupId);
      assertEquals(tenantA, created.getString("organizationId"));
      assertEquals(0L, fetchRemainingGatewayGroupQuota(tokenA));

      // Second creation for tenant A must be rejected with 429 Too Many Requests.
      given()
            .header("Authorization", "Bearer " + tokenA)
            .contentType(ContentType.JSON)
            .body(new GatewayGroupDTO(null, null, "gg-beta", Map.of()))
            .when()
            .post("/api/v1/gatewayGroups")
            .then()
            .statusCode(429);

      // Tenant B still has its own quota and is unaffected by tenant A's exhaustion.
      assertEquals(2L, fetchRemainingGatewayGroupQuota(tokenB));

      // Deleting tenant A's gateway group triggers @ReleaseQuota and restores remaining quota to 1.
      given()
            .header("Authorization", "Bearer " + tokenA)
            .when()
            .delete("/api/v1/gatewayGroups/{id}", gatewayGroupId)
            .then()
            .statusCode(204);
      assertEquals(1L, fetchRemainingGatewayGroupQuota(tokenA));
   }

   @Test
   void getApiTokensResolvesTenantFromSecurityIdentity() {
      String tenant = "test-org-" + UUID.randomUUID();
      String token = signToken(tenant);

      List<?> tokens = given()
            .header("Authorization", "Bearer " + token)
            .when()
            .get("/api/v1/tokens/apiTokens")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("$");
      assertTrue(tokens.isEmpty());
   }
}

