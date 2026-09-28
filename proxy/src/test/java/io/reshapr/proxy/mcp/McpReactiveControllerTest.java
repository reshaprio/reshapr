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
package io.reshapr.proxy.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpReactiveController.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class McpReactiveControllerTest {

    @Mock
    GatewayRegistry gatewayRegistry;

    @Mock
    HttpHeaders headers;

    @InjectMocks
    McpReactiveController controller;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testHandleHttpStreamable_ServiceNotFound() {
        when(gatewayRegistry.getService("bad-service")).thenReturn(null);

        McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION, 
                "req-1", 
                McpSchema.METHOD_INITIALIZE, 
                null);

        Uni<Response> responseUni = controller.handleHttpStreamable("bad-service", request, headers);
        Response response = responseUni.await().indefinitely();

        assertNotNull(response);
    }

    @Test
    void testHandleHttpStreamable_InitializeSuccess() {
        ServiceEntry serviceEntry = new ServiceEntry("svc-1", "org1", "TestService", "1.0", null, null);
        when(gatewayRegistry.getService("svc-1")).thenReturn(serviceEntry);

        McpSchema.InitializeRequest initParams = new McpSchema.InitializeRequest(
                "2024-11-05",
                new McpSchema.ClientCapabilities(null, null, null),
                new McpSchema.Implementation("TestClient", "1.0")
        );
        McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_INITIALIZE,
                "req-1",
                mapper.valueToTree(initParams)
        );

        Uni<Response> responseUni = controller.handleHttpStreamable("svc-1", request, headers);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        McpSchema.JSONRPCResponse jsonRpcResponse = (McpSchema.JSONRPCResponse) response.getEntity();
        assertNull(jsonRpcResponse.error());
        assertNotNull(jsonRpcResponse.result());
        assertTrue(jsonRpcResponse.result() instanceof McpSchema.InitializeResult);
        
        McpSchema.InitializeResult initResult = (McpSchema.InitializeResult) jsonRpcResponse.result();
        assertEquals("2024-11-05", initResult.protocolVersion());
        assertEquals("TestService MCP server", initResult.serverInfo().name());
    }

    @Test
    void testHandleHttpStreamable_UnsupportedProtocolVersion() {
        ServiceEntry serviceEntry = new ServiceEntry("svc-1", "org1", "TestService", "1.0", null, null);
        when(gatewayRegistry.getService("svc-1")).thenReturn(serviceEntry);

        McpSchema.InitializeRequest initParams = new McpSchema.InitializeRequest(
                "2023-11-05", // unsupported version
                new McpSchema.ClientCapabilities(null, null, null),
                new McpSchema.Implementation("TestClient", "1.0")
        );
        McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_INITIALIZE,
                "req-2",
                mapper.valueToTree(initParams)
        );

        Uni<Response> responseUni = controller.handleHttpStreamable("svc-1", request, headers);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        McpSchema.JSONRPCResponse jsonRpcResponse = (McpSchema.JSONRPCResponse) response.getEntity();
        assertNull(jsonRpcResponse.error());
        assertTrue(jsonRpcResponse.result() instanceof McpError);
        McpError error = (McpError) jsonRpcResponse.result();
        assertEquals("Unsupported protocol version: 2023-11-05", error.getMessage());
    }

    @Test
    void testHandleHttpStreamable_MethodNotFound() {
        ServiceEntry serviceEntry = new ServiceEntry("svc-1", "org1", "TestService", "1.0", null, null);
        when(gatewayRegistry.getService("svc-1")).thenReturn(serviceEntry);

        McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                "unknown/method",
                "req-3",
                null
        );

        Uni<Response> responseUni = controller.handleHttpStreamable("svc-1", request, headers);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        McpSchema.JSONRPCResponse.JSONRPCError error = (McpSchema.JSONRPCResponse.JSONRPCError) response.getEntity();
        assertEquals(McpSchema.ErrorCodes.METHOD_NOT_FOUND, error.code());
    }
    
    @Test
    void testHandleHttpStreamable_WithOrgServiceVersion() {
        ServiceEntry serviceEntry = new ServiceEntry("svc-1", "org1", "Test Service", "1.0", null, null);
        when(gatewayRegistry.getService("org1", "Test Service", "1.0")).thenReturn(serviceEntry);

        McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                "unknown/method",
                "req-4",
                null
        );

        // Note: the controller replaces '+' with ' '
        Uni<Response> responseUni = controller.handleHttpStreamable("org1", "Test+Service", "1.0", request, headers);
        Response response = responseUni.await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        McpSchema.JSONRPCResponse.JSONRPCError error = (McpSchema.JSONRPCResponse.JSONRPCError) response.getEntity();
        assertEquals(McpSchema.ErrorCodes.METHOD_NOT_FOUND, error.code());
    }
}
