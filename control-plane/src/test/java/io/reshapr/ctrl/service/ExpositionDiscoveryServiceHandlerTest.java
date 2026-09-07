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

import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ExpositionDiscoveryServiceHandlerTest {

    @Mock
    private ExpositionManagerService expositionManagerService;

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private StreamObserver<ExpositionDiscoveryResponse> responseObserver;

    private ExpositionDiscoveryServiceHandler handler;

    @BeforeEach
    void setup() {
        handler = new ExpositionDiscoveryServiceHandler(expositionManagerService, artifactRepository);
    }

    @Test
    void testDiscoverExpositions() throws Exception {
        ExpositionDiscoveryRequest request = ExpositionDiscoveryRequest.newBuilder()
                .setGatewayId("gw-1")
                .build();

        Exposition exposition = new Exposition();
        exposition.id = "exp-1";
        
        GatewayGroup group = new GatewayGroup();
        group.name = "group-1";
        exposition.gatewayGroup = group;
        
        ConfigurationPlan plan = new ConfigurationPlan();
        plan.id = "plan-1";
        plan.name = "plan-1";
        plan.backendEndpoint = "http://backend";
        
        Service service = new Service();
        service.id = "srv-1";
        service.name = "srv";
        service.version = "1.0";
        service.organizationId = "org-1";
        service.type = ServiceType.REST;
        plan.service = service;
        exposition.service = service;
        
        exposition.configurationPlan = plan;

        when(expositionManagerService.getGatewayExpositions(eq("gw-1"), any(), any(), nullable(String.class)))
                .thenReturn(List.of(exposition));

        handler.discoverExpositions(request, responseObserver);

        ArgumentCaptor<ExpositionDiscoveryResponse> captor = ArgumentCaptor.forClass(ExpositionDiscoveryResponse.class);
        verify(responseObserver, times(1)).onNext(captor.capture());
        verify(responseObserver, times(1)).onCompleted();

        ExpositionDiscoveryResponse response = captor.getValue();
        assertEquals(1, response.getExpositionsCount());
        assertEquals("exp-1", response.getExpositions(0).getId());
    }
}
