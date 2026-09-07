package io.reshapr.ctrl.rest.v1;

import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.service.ConfigurationPlanManagerService;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.InvalidConfigurationException;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.List;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for the ConfigurationPlanResource REST endpoints.
 * Provides coverage for configuration plan creation, duplication, updating, and API key renewal.
 *
 * @author vaishnav
 */
@QuarkusTest
@TestSecurity(user = "test-user", roles = {"user"})
class ConfigurationPlanResourceTest {

    @InjectMock
    ConfigurationPlanManagerService managerService;

    @InjectMock
    ConfigurationPlanRepository configurationPlanRepository;

    @BeforeEach
    void setup() {
        Mockito.reset(managerService, configurationPlanRepository);
    }

    @Test
    void testGetConfigurationPlans() {
        ConfigurationPlan plan = new ConfigurationPlan();
        plan.id = "plan-1";
        plan.name = "Test Plan";
        
        Mockito.when(managerService.getConfigurationPlans("svc-1")).thenReturn(List.of(plan));

        given()
            .queryParam("serviceId", "svc-1")
            .when().get("/api/v1/configurationPlans")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("$", hasSize(1))
            .body("[0].id", equalTo("plan-1"))
            .body("[0].name", equalTo("Test Plan"));
    }

    @Test
    void testCreateConfigurationPlanSuccess() throws Exception {
        ConfigurationPlan createdPlan = new ConfigurationPlan();
        createdPlan.id = "new-plan";
        createdPlan.name = "New Plan";
        createdPlan.apiKey = "secret-key";

        Mockito.when(managerService.createConfigurationPlan(any(), eq("svc-1"), eq("sec-1"), eq(true)))
               .thenReturn(createdPlan);

        given()
            .contentType(ContentType.JSON)
            .body("""
                  {
                    "name": "New Plan",
                    "serviceId": "svc-1",
                    "backendSecretId": "sec-1",
                    "apiKey": "true"
                  }
                  """)
            .when().post("/api/v1/configurationPlans")
            .then()
            .statusCode(Response.Status.CREATED.getStatusCode())
            .body("id", equalTo("new-plan"))
            .body("apiKey", equalTo("secret-key"));
    }

    @Test
    void testCreateConfigurationPlanDependencyNotFound() throws Exception {
        Mockito.when(managerService.createConfigurationPlan(any(), anyString(), anyString(), anyBoolean()))
               .thenThrow(new DependencyNotFoundException("Service not found"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                  {
                    "name": "Invalid Plan",
                    "serviceId": "bad-svc",
                    "backendSecretId": "sec-1"
                  }
                  """)
            .when().post("/api/v1/configurationPlans")
            .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    void testDuplicateConfigurationPlanSuccess() throws Exception {
        ConfigurationPlan dupPlan = new ConfigurationPlan();
        dupPlan.id = "dup-plan";
        dupPlan.name = "Cloned Plan";
        dupPlan.apiKey = "new-key";

        Mockito.when(managerService.duplicateConfigurationPlan("plan-1", "Cloned Plan"))
               .thenReturn(dupPlan);

        given()
            .queryParam("name", "Cloned Plan")
            .when().post("/api/v1/configurationPlans/plan-1/duplicate")
            .then()
            .statusCode(Response.Status.CREATED.getStatusCode())
            .body("id", equalTo("dup-plan"))
            .body("name", equalTo("Cloned Plan"))
            .body("apiKey", equalTo("new-key"));
    }

    @Test
    void testGetConfigurationPlanNotFound() {
        Mockito.when(configurationPlanRepository.findById("999")).thenReturn(null);

        given()
            .when().get("/api/v1/configurationPlans/999")
            .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    void testUpdateConfigurationPlanSuccess() throws Exception {
        ConfigurationPlan existing = new ConfigurationPlan();
        existing.id = "plan-1";
        existing.name = "Old Name";

        ConfigurationPlan updated = new ConfigurationPlan();
        updated.id = "plan-1";
        updated.name = "Updated Name";

        Mockito.when(configurationPlanRepository.findById("plan-1")).thenReturn(existing);
        Mockito.when(managerService.updateConfigurationPlan(any(), eq("sec-1"))).thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body("""
                  {
                    "id": "plan-1",
                    "name": "Updated Name",
                    "backendSecretId": "sec-1"
                  }
                  """)
            .when().put("/api/v1/configurationPlans/plan-1")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("name", equalTo("Updated Name"));
    }

    @Test
    void testRenewApiKeySuccess() {
        ConfigurationPlan existing = new ConfigurationPlan();
        existing.id = "plan-1";

        ConfigurationPlan renewed = new ConfigurationPlan();
        renewed.id = "plan-1";
        renewed.apiKey = "new-shiny-key";

        Mockito.when(configurationPlanRepository.findById("plan-1")).thenReturn(existing);
        Mockito.when(managerService.renewApiKey(existing)).thenReturn(renewed);

        given()
            .when().put("/api/v1/configurationPlans/plan-1/renewApiKey")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("apiKey", equalTo("new-shiny-key"));
    }

    @Test
    void testDeleteConfigurationPlanSuccess() {
        ConfigurationPlan existing = new ConfigurationPlan();
        existing.id = "plan-1";

        Mockito.when(configurationPlanRepository.findById("plan-1")).thenReturn(existing);

        given()
            .when().delete("/api/v1/configurationPlans/plan-1")
            .then()
            .statusCode(Response.Status.NO_CONTENT.getStatusCode());
        
        Mockito.verify(managerService).deleteConfigurationPlan(existing);
    }
}
