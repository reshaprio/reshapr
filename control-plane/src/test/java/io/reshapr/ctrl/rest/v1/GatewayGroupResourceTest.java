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

import io.quarkus.security.identity.SecurityIdentity;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.security.ReshaprTenantResolver;
import io.reshapr.ctrl.service.GatewayGroupManagerService;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class GatewayGroupResourceTest {

    @Mock
    private GatewayGroupManagerService managerService;

    @Mock
    private GatewayGroupRepository gatewayGroupRepository;

    @Mock
    private Mappers v1Mappers;

    @Mock
    private SecurityIdentity securityIdentity;

    private GatewayGroupResource gatewayGroupResource;

    @BeforeEach
    void setup() {
        gatewayGroupResource = new GatewayGroupResource(managerService, gatewayGroupRepository, v1Mappers);
        gatewayGroupResource.securityIdentity = securityIdentity;
    }

    @Test
    void testGetGatewayGroupsList() {
        GatewayGroup gatewayGroup = new GatewayGroup();
        GatewayGroupDTO dto = new GatewayGroupDTO("gg-1", "org-1", "My Group", null);

        when(managerService.getAvailableGatewayGroups()).thenReturn(List.of(gatewayGroup));
        when(v1Mappers.toGGResources(List.of(gatewayGroup))).thenReturn(List.of(dto));

        List<GatewayGroupDTO> result = gatewayGroupResource.getGatewayGroups();
        assertEquals(1, result.size());
        assertEquals("gg-1", result.get(0).id());
    }

    @Test
    void testGetGatewayGroupByIdSuccess() {
        GatewayGroup gatewayGroup = new GatewayGroup();
        GatewayGroupDTO dto = new GatewayGroupDTO("gg-1", "org-1", "My Group", null);

        when(gatewayGroupRepository.findById("gg-1")).thenReturn(gatewayGroup);
        when(v1Mappers.toResource(gatewayGroup)).thenReturn(dto);

        Response response = gatewayGroupResource.getGatewayGroups("gg-1");
        assertEquals(200, response.getStatus());
        assertEquals(dto, response.getEntity());
    }

    @Test
    void testGetGatewayGroupByIdNotFound() {
        when(gatewayGroupRepository.findById("gg-1")).thenReturn(null);

        Response response = gatewayGroupResource.getGatewayGroups("gg-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testCreateGatewayGroupSuccess() {
        GatewayGroupDTO requestDto = new GatewayGroupDTO(null, null, "My Group", Map.of("env", "prod"));
        GatewayGroup gatewayGroup = new GatewayGroup();
        gatewayGroup.name = "My Group";

        GatewayGroupDTO responseDto = new GatewayGroupDTO("gg-1", "org-1", "My Group", Map.of("env", "prod"));

        when(v1Mappers.fromResource(requestDto)).thenReturn(gatewayGroup);
        when(securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-1");
        when(v1Mappers.toResource(gatewayGroup)).thenReturn(responseDto);

        Response response = gatewayGroupResource.createGatewayGroup(requestDto);
        assertEquals(201, response.getStatus());
        assertEquals(responseDto, response.getEntity());
        assertEquals("org-1", gatewayGroup.organizationId);
        
        verify(gatewayGroupRepository, times(1)).persistAndFlush(gatewayGroup);
    }

    @Test
    void testCreateGatewayGroupUnauthorized() {
        gatewayGroupResource.securityIdentity = null;
        GatewayGroupDTO requestDto = new GatewayGroupDTO(null, null, "My Group", null);

        Response response = gatewayGroupResource.createGatewayGroup(requestDto);
        assertEquals(401, response.getStatus());
    }

    @Test
    void testUpdateGatewayGroupSuccess() {
        GatewayGroupDTO requestDto = new GatewayGroupDTO("gg-1", "org-1", "Updated Group", Map.of("env", "dev"));
        GatewayGroup gatewayGroup = new GatewayGroup();
        gatewayGroup.id = "gg-1";
        gatewayGroup.name = "Old Group";

        GatewayGroupDTO responseDto = new GatewayGroupDTO("gg-1", "org-1", "Updated Group", Map.of("env", "dev"));

        when(gatewayGroupRepository.findById("gg-1")).thenReturn(gatewayGroup);
        when(v1Mappers.toResource(gatewayGroup)).thenReturn(responseDto);

        Response response = gatewayGroupResource.updateGatewayGroup("gg-1", requestDto);
        assertEquals(200, response.getStatus());
        assertEquals(responseDto, response.getEntity());
        assertEquals("Updated Group", gatewayGroup.name);
        assertEquals("dev", gatewayGroup.labels.get("env"));

        verify(gatewayGroupRepository, times(1)).persist(gatewayGroup);
    }

    @Test
    void testUpdateGatewayGroupNotFound() {
        GatewayGroupDTO requestDto = new GatewayGroupDTO("gg-1", "org-1", "Updated Group", null);
        when(gatewayGroupRepository.findById("gg-1")).thenReturn(null);

        Response response = gatewayGroupResource.updateGatewayGroup("gg-1", requestDto);
        assertEquals(404, response.getStatus());
    }

    @Test
    void testDeleteGatewayGroupSuccess() {
        GatewayGroup gatewayGroup = new GatewayGroup();
        when(gatewayGroupRepository.findById("gg-1")).thenReturn(gatewayGroup);

        Response response = gatewayGroupResource.deleteGatewayGroup("gg-1");
        assertEquals(204, response.getStatus());

        verify(gatewayGroupRepository, times(1)).delete(gatewayGroup);
    }

    @Test
    void testDeleteGatewayGroupNotFound() {
        when(gatewayGroupRepository.findById("gg-1")).thenReturn(null);

        Response response = gatewayGroupResource.deleteGatewayGroup("gg-1");
        assertEquals(404, response.getStatus());
    }
}
