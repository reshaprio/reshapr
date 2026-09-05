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
package io.reshapr.ctrl.security;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.Context;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReshaprTenantResolver.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ReshaprTenantResolverTest {

    @Mock
    RoutingContext routingContext;

    @Mock
    CurrentVertxRequest vertxRequest;

    @InjectMocks
    ReshaprTenantResolver tenantResolver;

    @Test
    void testGetDefaultTenantId() {
        assertEquals(ReshaprTenantResolver.DEFAULT_TENANT_ID, tenantResolver.getDefaultTenantId());
    }

    @Test
    void testIsRoot() {
        assertTrue(tenantResolver.isRoot(ReshaprTenantResolver.ROOT_TENANT_ID));
        assertFalse(tenantResolver.isRoot("other-tenant"));
        assertFalse(tenantResolver.isRoot(null));
    }

    @Test
    void testResolveTenantId_FromVertxContext() {
        when(vertxRequest.getCurrent()).thenReturn(routingContext);

        try (MockedStatic<Vertx> vertxMock = mockStatic(Vertx.class)) {
            Context mockContext = mock(Context.class);
            vertxMock.when(Vertx::currentContext).thenReturn(mockContext);
            when(mockContext.getLocal(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-vertx");

            String tenantId = tenantResolver.resolveTenantId();
            assertEquals("org-vertx", tenantId);
        }
    }

    @Test
    void testResolveTenantId_FromTenantContext() {
        when(vertxRequest.getCurrent()).thenReturn(routingContext);

        try (MockedStatic<Vertx> vertxMock = mockStatic(Vertx.class);
             MockedStatic<ReshaprTenantContext> tenantContextMock = mockStatic(ReshaprTenantContext.class)) {
            
            vertxMock.when(Vertx::currentContext).thenReturn(null);
            tenantContextMock.when(ReshaprTenantContext::getCurrentTenant).thenReturn("org-tenant-context");

            String tenantId = tenantResolver.resolveTenantId();
            assertEquals("org-tenant-context", tenantId);
        }
    }

    @Test
    void testResolveTenantId_FallbackToDefault() {
        when(vertxRequest.getCurrent()).thenReturn(routingContext);

        try (MockedStatic<Vertx> vertxMock = mockStatic(Vertx.class);
             MockedStatic<ReshaprTenantContext> tenantContextMock = mockStatic(ReshaprTenantContext.class)) {
            
            vertxMock.when(Vertx::currentContext).thenReturn(null);
            tenantContextMock.when(ReshaprTenantContext::getCurrentTenant).thenReturn(null);

            String tenantId = tenantResolver.resolveTenantId();
            assertEquals(ReshaprTenantResolver.DEFAULT_TENANT_ID, tenantId);
        }
    }
}
