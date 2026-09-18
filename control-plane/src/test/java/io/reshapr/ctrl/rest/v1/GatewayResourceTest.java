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

import io.reshapr.ctrl.model.Gateway;
import io.reshapr.ctrl.service.GatewayManagerService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class GatewayResourceTest {

    @Mock
    private GatewayManagerService gatewayManagerService;

    @Mock
    private Mappers v1Mappers;

    private GatewayResource gatewayResource;

    @BeforeEach
    void setup() {
        gatewayResource = new GatewayResource(gatewayManagerService, v1Mappers);
    }

    @Test
    void testListGateways() {
        Gateway gateway = new Gateway();
        gateway.name = "my-gateway";

        GatewayViewDTO dto = new GatewayViewDTO(
                "gw-1",
                null,
                "my-gateway",
                "1.0.0",
                null,
                null,
                List.of(),
                null,
                null
        );

        when(gatewayManagerService.getActiveGateways()).thenReturn(List.of(gateway));
        when(v1Mappers.toGWResources(List.of(gateway))).thenReturn(List.of(dto));

        List<GatewayViewDTO> result = gatewayResource.listGateways();

        assertEquals(1, result.size());
        assertEquals("my-gateway", result.get(0).name());
    }
}
