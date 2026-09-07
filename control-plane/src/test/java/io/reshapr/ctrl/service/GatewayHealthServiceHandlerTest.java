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

import io.reshapr.health.gateway.v1.GatewayHealthResponse;
import io.reshapr.health.gateway.v1.GatewayRequest;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayHealthServiceHandler.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class GatewayHealthServiceHandlerTest {

    @Mock
    GatewayManagerService gatewayManagerService;

    @Mock
    StreamObserver<GatewayHealthResponse> responseObserver;

    @InjectMocks
    GatewayHealthServiceHandler gatewayHealthServiceHandler;

    @Test
    void testAdvertHealthy_Acknowledged() {
        GatewayRequest request = GatewayRequest.newBuilder().setGatewayId("gw-1").build();
        when(gatewayManagerService.updateGatewayHeartbeat("gw-1")).thenReturn(true);

        gatewayHealthServiceHandler.advertHealthy(request, responseObserver);

        verify(gatewayManagerService).updateGatewayHeartbeat("gw-1");

        ArgumentCaptor<GatewayHealthResponse> responseCaptor = ArgumentCaptor.forClass(GatewayHealthResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        assertTrue(responseCaptor.getValue().getAcknowledged());
    }

    @Test
    void testAdvertHealthy_NotAcknowledged() {
        GatewayRequest request = GatewayRequest.newBuilder().setGatewayId("gw-unknown").build();
        when(gatewayManagerService.updateGatewayHeartbeat("gw-unknown")).thenReturn(false);

        gatewayHealthServiceHandler.advertHealthy(request, responseObserver);

        verify(gatewayManagerService).updateGatewayHeartbeat("gw-unknown");

        ArgumentCaptor<GatewayHealthResponse> responseCaptor = ArgumentCaptor.forClass(GatewayHealthResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        assertFalse(responseCaptor.getValue().getAcknowledged());
    }

    @Test
    void testAdvertShutdown() {
        GatewayRequest request = GatewayRequest.newBuilder().setGatewayId("gw-1").build();

        gatewayHealthServiceHandler.advertShutdown(request, responseObserver);

        verify(gatewayManagerService).unregisterGateway("gw-1");

        ArgumentCaptor<GatewayHealthResponse> responseCaptor = ArgumentCaptor.forClass(GatewayHealthResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        assertTrue(responseCaptor.getValue().getAcknowledged());
    }
}
