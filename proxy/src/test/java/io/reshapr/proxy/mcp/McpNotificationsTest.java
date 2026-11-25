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
package io.reshapr.proxy.mcp;

import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Client notifications are acknowledged with a bare 202, never with a JSON-RPC error.
 * @author laurent
 */
@QuarkusTest
class McpNotificationsTest {

   private static final String EXPOSITION_ID = "notifications-exp";

   @Inject
   GatewayRegistry registry;

   @BeforeEach
   void seedExposition() {
      ServiceEntry service = new ServiceEntry("notifications-svc", "acme", "Notifications API", "1.0.0", "REST", List.of());
      ConfigurationEntry configuration = new ConfigurationEntry("notifications-cfg", "cfg",
            "http://backend", null, List.of(), List.of(), null, null, null);
      registry.addExposition(new ExpositionEntry(EXPOSITION_ID, "notifications-exp", service, configuration, null, List.of()));
   }

   @Test
   void cancelledNotificationIsAcceptedInStatelessMode() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body("""
                  {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"7","reason":"user"}}""")
            .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
            .then()
            .statusCode(202)
            .body(emptyOrNullString());
   }

   @Test
   void initializedNotificationIsAcceptedWithSessionHeader() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_SESSION_ID, "some-session-id")
            .body("""
                  {"jsonrpc":"2.0","method":"notifications/initialized"}""")
            .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
            .then()
            .statusCode(202)
            .body(emptyOrNullString());
   }

   @Test
   void initializedNotificationIsAcceptedInStatelessMode() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body("""
                  {"jsonrpc":"2.0","method":"notifications/initialized"}""")
            .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
            .then()
            .statusCode(202)
            .body(emptyOrNullString());
   }

   @Test
   void initializedNotificationWithoutSessionNorStatelessVersionIsRejected() {
      given()
            .contentType(ContentType.JSON)
            .body("""
                  {"jsonrpc":"2.0","method":"notifications/initialized"}""")
            .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
            .then()
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_REQUEST));
   }

   @Test
   void unknownRequestsAreStillRejected() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body("""
                  {"jsonrpc":"2.0","id":1,"method":"does/not/exist"}""")
            .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
            .then()
            .body("error.code", equalTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND));
   }
}
