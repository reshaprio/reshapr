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
package io.reshapr.ctrl.rest.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * @author vaishnav
 */
@QuarkusIntegrationTest
class OrganizationResourceIT {

    // Using the default API key configured in application.properties
    private static final String DEFAULT_API_KEY = "CzBuQ9B0i8qrUQe6WLiDLqR3gv4iCbxvjTJQP0z0CFGQbjgBHPZSusa9d1gZKwwjdoCsJ8ogRwRzc06GipJSjSDkFOy0BSOKvAa2EjU3As9I5UjgizTzxsJAVJIXtdo2xiXHhcry9KeJa0zRhDtGmm8WMujoXrlfj0ChlJKaHZiZsRthd4UHrWkKur9KySXpPFP21H4C0Cq6OgM1rJpvMZ7Jd2ZzeEcd5lKE4PlchHZBVEdu8jYzjQtU50fkOPoR";

    @Test
    void testCreateAndGetOrganization() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String orgName = "integration-org-" + uniqueSuffix;

        // 1. Create a new organization
        given()
            .header("x-reshapr-api-key", DEFAULT_API_KEY)
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "%s",
                    "description": "Integration test organization",
                    "icon": "fa-building"
                }
                """.formatted(orgName))
        .when()
            .post("/api/admin/organizations")
        .then()
            .statusCode(201)
            .body("name", is(orgName))
            .body("description", is("Integration test organization"));

        // 2. Retrieve the organization to verify persistence
        given()
            .header("x-reshapr-api-key", DEFAULT_API_KEY)
        .when()
            .get("/api/admin/organizations?size=100")
        .then()
            .statusCode(200)
            .body("find { it.name == '" + orgName + "' }", notNullValue())
            .body("find { it.name == '" + orgName + "' }.description", is("Integration test organization"));
    }

    @Test
    void testUnauthorizedAccess() {
        given()
            .header("x-reshapr-api-key", "invalid-api-key")
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "unauthorized-org",
                    "description": "Should fail",
                    "icon": "fa-times"
                }
                """)
        .when()
            .post("/api/admin/organizations")
        .then()
            .statusCode(401);
    }
}
