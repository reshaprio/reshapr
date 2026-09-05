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

import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.SharedResource;
import io.reshapr.ctrl.model.SharedResourceTypes;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.security.ReshaprTenantResolver;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.vertx.mutiny.core.Context;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class GatewayGroupManagerServiceTest {

    @Mock
    private GatewayGroupRepository gatewayGroupRepository;

    @Mock
    private Context vertxContext;

    @Mock
    private PanacheQuery<GatewayGroup> panacheQuery;

    private GatewayGroupManagerService gatewayGroupManagerService;

    private MockedStatic<Vertx> mockedVertx;
    private MockedStatic<SharedResource> mockedSharedResource;

    @BeforeEach
    void setup() {
        gatewayGroupManagerService = new GatewayGroupManagerService(gatewayGroupRepository);
        mockedVertx = mockStatic(Vertx.class);
        mockedSharedResource = mockStatic(SharedResource.class);
    }

    @AfterEach
    void teardown() {
        mockedVertx.close();
        mockedSharedResource.close();
    }

    @Test
    void testGetOwnedGatewayGroups() {
        when(Vertx.currentContext()).thenReturn(vertxContext);
        when(vertxContext.getLocal(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-1");

        GatewayGroup group = new GatewayGroup();
        group.id = "gg-1";
        
        when(gatewayGroupRepository.find("organizationId", "org-1")).thenReturn(panacheQuery);
        when(panacheQuery.list()).thenReturn(List.of(group));

        List<GatewayGroup> result = gatewayGroupManagerService.getOwnedGatewayGroups();
        assertEquals(1, result.size());
        assertEquals("gg-1", result.get(0).id);
    }

    @Test
    void testGetAvailableGatewayGroups() {
        when(Vertx.currentContext()).thenReturn(vertxContext);
        when(vertxContext.getLocal(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-1");

        SharedResource resource = new SharedResource();
        resource.resourceIds = List.of("gg-2", "gg-3");

        mockedSharedResource.when(() -> SharedResource.findByTypeAndOrganizationId(SharedResourceTypes.GATEWAY_GROUP, "org-1"))
                .thenReturn(List.of(resource));

        GatewayGroup group = new GatewayGroup();
        group.id = "gg-1"; // Assuming it finds owned + shared.
        
        when(gatewayGroupRepository.findOwnedAndWithIds("org-1", List.of("gg-2", "gg-3")))
                .thenReturn(List.of(group));

        List<GatewayGroup> result = gatewayGroupManagerService.getAvailableGatewayGroups();
        assertEquals(1, result.size());
        assertEquals("gg-1", result.get(0).id);
    }
}
