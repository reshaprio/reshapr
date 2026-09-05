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
package io.reshapr.ctrl.service;

import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ActiveExpositionRepository;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.repository.ExpositionRepository;
import io.reshapr.ctrl.repository.GatewayGroupRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ExpositionManagerServiceTest {

    @Mock
    private ExpositionRepository expositionRepository;

    @Mock
    private ConfigurationPlanRepository configurationPlanRepository;

    @Mock
    private GatewayGroupManagerService gatewayGroupManagerService;

    @Mock
    private ActiveExpositionRepository activeExpositionRepository;

    @Mock
    private GatewayManagerService gatewayManagerService;

    @Mock
    private GatewayGroupRepository gatewayGroupRepository;

    @Mock
    private ClusterEventBroadcaster clusterEventBroadcaster;

    @InjectMocks
    private ExpositionManagerService expositionManagerService;

    private ConfigurationPlan configurationPlan;
    private GatewayGroup gatewayGroup;

    @BeforeEach
    void setup() {
        Service service = new Service();
        service.name = "test-svc";
        service.version = "1.0.0";

        configurationPlan = new ConfigurationPlan();
        configurationPlan.id = "plan-1";
        configurationPlan.name = "default-plan";
        configurationPlan.service = service;

        gatewayGroup = new GatewayGroup();
        gatewayGroup.id = "group-1";
        gatewayGroup.name = "internal-gateways";
    }

    @Test
    void testExposeConfigurationSuccess() throws DependencyNotFoundException {
        when(configurationPlanRepository.findById("plan-1")).thenReturn(configurationPlan);
        when(gatewayGroupManagerService.getAvailableGatewayGroups()).thenReturn(List.of(gatewayGroup));
        doNothing().when(expositionRepository).persistAndFlush(any(Exposition.class));

        Exposition result = expositionManagerService.exposeConfiguration("plan-1", "group-1", "custom-name");

        assertNotNull(result);
        assertEquals("custom-name", result.name);
        assertEquals("plan-1", result.configurationPlan.id);
        assertEquals("group-1", result.gatewayGroup.id);
        verify(clusterEventBroadcaster, times(1)).publishExpositionCreationEvent(any(Exposition.class), eq(gatewayGroup));
    }

    @Test
    void testExposeConfigurationPlanNotFound() {
        when(configurationPlanRepository.findById("plan-1")).thenReturn(null);

        assertThrows(DependencyNotFoundException.class, () -> {
            expositionManagerService.exposeConfiguration("plan-1", "group-1", "custom-name");
        });
    }

    @Test
    void testExposeConfigurationGatewayGroupNotFound() {
        when(configurationPlanRepository.findById("plan-1")).thenReturn(configurationPlan);
        when(gatewayGroupManagerService.getAvailableGatewayGroups()).thenReturn(List.of());

        assertThrows(DependencyNotFoundException.class, () -> {
            expositionManagerService.exposeConfiguration("plan-1", "group-1", "custom-name");
        });
    }

    @Test
    void testSuggestExpositionNameUnique() {
        when(expositionRepository.findByName("test-svc-1-0-0-default-plan")).thenReturn(null);
        String name = expositionManagerService.suggestExpositionName(configurationPlan.service, configurationPlan);
        assertEquals("test-svc-1-0-0-default-plan", name);
    }

    @Test
    void testSuggestExpositionNameCollision() {
        when(expositionRepository.findByName("test-svc-1-0-0-default-plan")).thenReturn(new Exposition());
        when(expositionRepository.findByName("test-svc-1-0-0-default-plan-2")).thenReturn(null);

        String name = expositionManagerService.suggestExpositionName(configurationPlan.service, configurationPlan);
        assertEquals("test-svc-1-0-0-default-plan-2", name);
    }
}
