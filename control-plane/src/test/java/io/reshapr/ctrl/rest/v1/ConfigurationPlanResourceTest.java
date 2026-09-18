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

import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.service.ConfigurationPlanManagerService;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.InvalidConfigurationException;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ConfigurationPlanResourceTest {

    @Mock
    private ConfigurationPlanManagerService managerService;

    @Mock
    private ConfigurationPlanRepository configurationPlanRepository;

    @Mock
    private Mappers v1Mappers;

    private ConfigurationPlanResource configurationPlanResource;

    @BeforeEach
    void setup() {
        configurationPlanResource = new ConfigurationPlanResource(managerService, configurationPlanRepository, v1Mappers);
    }

    @Test
    void testGetConfigurationPlans() {
        ConfigurationPlan plan = new ConfigurationPlan();
        ConfigurationPlanDTO dto = new ConfigurationPlanDTO();
        dto.setId("plan-1");

        when(managerService.getConfigurationPlans("svc-1")).thenReturn(List.of(plan));
        when(v1Mappers.toCPResources(List.of(plan))).thenReturn(List.of(dto));

        List<ConfigurationPlanDTO> result = configurationPlanResource.getConfigurationPlans("svc-1");
        assertEquals(1, result.size());
        assertEquals("plan-1", result.get(0).getId());
    }

    @Test
    void testCreateConfigurationPlanSuccess() throws DependencyNotFoundException, io.reshapr.ctrl.service.InvalidConfigurationException {
        ConfigurationPlanDTO requestDto = new ConfigurationPlanDTO();
        requestDto.setServiceId("svc-1");
        requestDto.setBackendSecretId("backend-1");
        requestDto.setApiKey("api-key");
        
        ConfigurationPlan plan = new ConfigurationPlan();
        ConfigurationPlan createdPlan = new ConfigurationPlan();
        createdPlan.apiKey = "new-api-key";

        ConfigurationPlanDTO responseDto = new ConfigurationPlanDTO();
        responseDto.setId("plan-1");

        when(v1Mappers.fromResource(requestDto)).thenReturn(plan);
        when(managerService.createConfigurationPlan(eq(plan), eq("svc-1"), eq("backend-1"), anyBoolean())).thenReturn(createdPlan);
        when(v1Mappers.toResource(createdPlan)).thenReturn(responseDto);

        Response response = configurationPlanResource.createConfigurationPlan(requestDto);
        assertEquals(201, response.getStatus());

        ConfigurationPlanDTO returnedDto = (ConfigurationPlanDTO) response.getEntity();
        assertEquals("new-api-key", returnedDto.getApiKey());
    }

    @Test
    void testCreateConfigurationPlanDependencyNotFound() throws DependencyNotFoundException, io.reshapr.ctrl.service.InvalidConfigurationException {
        ConfigurationPlanDTO requestDto = new ConfigurationPlanDTO();
        requestDto.setServiceId("svc-1");
        requestDto.setBackendSecretId("backend-1");
        
        ConfigurationPlan plan = new ConfigurationPlan();

        when(v1Mappers.fromResource(requestDto)).thenReturn(plan);
        when(managerService.createConfigurationPlan(eq(plan), eq("svc-1"), eq("backend-1"), anyBoolean()))
                .thenThrow(new DependencyNotFoundException("Not found"));

        Response response = configurationPlanResource.createConfigurationPlan(requestDto);
        assertEquals(404, response.getStatus());
    }

    @Test
    void testDuplicateConfigurationPlanSuccess() {
        ConfigurationPlan newPlan = new ConfigurationPlan();
        newPlan.apiKey = "duplicate-api-key";
        ConfigurationPlanDTO responseDto = new ConfigurationPlanDTO();
        responseDto.setId("plan-2");

        when(managerService.duplicateConfigurationPlan("plan-1", "New Plan")).thenReturn(newPlan);
        when(v1Mappers.toResource(newPlan)).thenReturn(responseDto);

        Response response = configurationPlanResource.duplicateConfigurationPlan("plan-1", "New Plan");
        assertEquals(201, response.getStatus());
        assertEquals("duplicate-api-key", ((ConfigurationPlanDTO) response.getEntity()).getApiKey());
    }

    @Test
    void testDeleteConfigurationPlanSuccess() {
        ConfigurationPlan plan = new ConfigurationPlan();
        when(configurationPlanRepository.findById("plan-1")).thenReturn(plan);
        doNothing().when(managerService).deleteConfigurationPlan(plan);

        Response response = configurationPlanResource.deleteConfigurationPlan("plan-1");
        assertEquals(204, response.getStatus());
        verify(managerService, times(1)).deleteConfigurationPlan(plan);
    }
}
