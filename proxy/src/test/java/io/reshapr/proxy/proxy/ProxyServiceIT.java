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
package io.reshapr.proxy.proxy;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * @author vaishnav
 */
@QuarkusIntegrationTest
class ProxyServiceIT {

    // The default API key or we can just expect 401/404 for unconfigured endpoints
    private static final String DEFAULT_API_KEY = "CzBuQ9B0i8qrUQe6WLiDLqR3gv4iCbxvjTJQP0z0CFGQbjgBHPZSusa9d1gZKwwjdoCsJ8ogRwRzc06GipJSjSDkFOy0BSOKvAa2EjU3As9I5UjgizTzxsJAVJIXtdo2xiXHhcry9KeJa0zRhDtGmm8WMujoXrlfj0ChlJKaHZiZsRthd4UHrWkKur9KySXpPFP21H4C0Cq6OgM1rJpvMZ7Jd2ZzeEcd5lKE4PlchHZBVEdu8jYzjQtU50fkOPoR";

    @Test
    void testUnknownExpositionReturns404() {
        // Hitting the MCP endpoint with an unknown exposition ID
        given()
            .header("Authorization", "Bearer " + DEFAULT_API_KEY)
            .contentType(ContentType.JSON)
            .body("""
                {
                    "jsonrpc": "2.0",
                    "method": "server/discover",
                    "id": 1
                }
                """)
        .when()
            .post("/mcp/unknown-exposition-id")
        .then()
            .statusCode(404)
            .body(containsString("not found"));
    }

    @Test
    void testUnknownOrganizationExpositionReturns404() {
        given()
            .header("Authorization", "Bearer " + DEFAULT_API_KEY)
            .contentType(ContentType.JSON)
            .body("""
                {
                    "jsonrpc": "2.0",
                    "method": "server/discover",
                    "id": 2
                }
                """)
        .when()
            .post("/mcp/unknown-org/unknown-exposition")
        .then()
            .statusCode(404)
            .body(containsString("not found"));
    }
}
