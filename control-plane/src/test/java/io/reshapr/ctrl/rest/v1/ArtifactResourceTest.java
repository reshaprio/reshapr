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

import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.control.ActivateRequestContext;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

/**
 * Integration tests for {@link ArtifactResource}, focusing on the propagation of artifact validation
 * errors as clean {@code 400 Bad Request} responses carrying a structured {@code ErrorDTO} message
 * rather than an opaque {@code 500}.
 * @author laurent
 */
@QuarkusTest
@TestProfile(ArtifactResourceTest.RandomPostgresPortProfile.class)
@ActivateRequestContext
class ArtifactResourceTest {

   private String bearerToken;

   @BeforeEach
   void setup() {
      String currentTenant = "test-org-" + UUID.randomUUID();
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
   void attachInvalidArtifactReturnsBadRequestWithMessage() {
      File invalid = new File(Thread.currentThread().getContextClassLoader()
            .getResource("io/reshapr/ctrl/artifacts/custom-tools-no-arguments-invalid.yaml").getFile());

      given()
            .header("Authorization", "Bearer " + bearerToken)
            .contentType(ContentType.MULTIPART)
            .multiPart("file", invalid)
            .when()
            .post("/api/v1/artifacts/attach")
            .then()
            .statusCode(400)
            .contentType(ContentType.JSON)
            .body("message", not(emptyOrNullString()))
            .body("message", containsString("CustomTools"));
   }

   public static class RandomPostgresPortProfile implements QuarkusTestProfile {
      @Override
      public Map<String, String> getConfigOverrides() {
         return Map.of("quarkus.datasource.devservices.port", "0");
      }
   }
}
