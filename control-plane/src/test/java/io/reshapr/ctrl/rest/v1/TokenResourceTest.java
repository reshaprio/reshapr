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
import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.repository.ApiTokenRepository;
import io.reshapr.ctrl.security.ReshaprTenantResolver;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.TokenManagerService;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class TokenResourceTest {

    @Mock
    private TokenManagerService tokenManagerService;

    @Mock
    private ApiTokenRepository apiTokenRepository;

    @Mock
    private Mappers v1Mappers;

    @Mock
    private SecurityIdentity securityIdentity;

    @Mock
    private Principal principal;

    private TokenResource tokenResource;

    @BeforeEach
    void setup() {
        tokenResource = new TokenResource(tokenManagerService, apiTokenRepository, v1Mappers);
        tokenResource.securityIdentity = securityIdentity;
    }

    @Test
    void testGetApiTokens() {
        ApiToken apiToken = new ApiToken();
        ApiTokenDTO dto = new ApiTokenDTO();
        dto.setId("token-1");

        when(securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-1");
        when(tokenManagerService.getApiTokens("org-1")).thenReturn(List.of(apiToken));
        when(v1Mappers.toATResources(List.of(apiToken))).thenReturn(List.of(dto));

        List<ApiTokenDTO> result = tokenResource.getApiTokens();
        assertEquals(1, result.size());
        assertEquals("token-1", result.get(0).getId());
    }

    @Test
    void testCreateApiTokenSuccess() throws Exception {
        ApiTokenRequestDTO requestDto = new ApiTokenRequestDTO("My Token", ValidityPeriodEnum.THIRTY_DAYS);
        
        when(securityIdentity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");
        when(securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-1");

        ApiToken createdToken = new ApiToken();
        createdToken.token = "secret-token-value";
        ApiTokenDTO responseDto = new ApiTokenDTO();
        responseDto.setId("token-1");

        when(tokenManagerService.generateApiToken("My Token", "org-1", 30, "testuser")).thenReturn(createdToken);
        when(v1Mappers.toResource(createdToken)).thenReturn(responseDto);

        Response response = tokenResource.createApiToken(requestDto);
        assertEquals(201, response.getStatus());
        
        ApiTokenDTO returnedDto = (ApiTokenDTO) response.getEntity();
        assertEquals("secret-token-value", returnedDto.getToken());
    }

    @Test
    void testCreateApiTokenDependencyNotFound() throws Exception {
        ApiTokenRequestDTO requestDto = new ApiTokenRequestDTO("My Token", ValidityPeriodEnum.THIRTY_DAYS);
        
        when(securityIdentity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");
        when(securityIdentity.getAttribute(ReshaprTenantResolver.TENANT_ID_CONTEXT_KEY)).thenReturn("org-1");

        when(tokenManagerService.generateApiToken(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new DependencyNotFoundException("Org not found"));

        Response response = tokenResource.createApiToken(requestDto);
        assertEquals(500, response.getStatus());
    }

    @Test
    void testDeleteApiTokenSuccess() {
        ApiToken apiToken = new ApiToken();
        when(apiTokenRepository.findById("token-1")).thenReturn(apiToken);
        doNothing().when(tokenManagerService).revokeApiToken(apiToken);

        Response response = tokenResource.deleteApiToken("token-1");
        assertEquals(204, response.getStatus());
        verify(tokenManagerService, times(1)).revokeApiToken(apiToken);
    }

    @Test
    void testDeleteApiTokenNotFound() {
        when(apiTokenRepository.findById("token-1")).thenReturn(null);

        Response response = tokenResource.deleteApiToken("token-1");
        assertEquals(404, response.getStatus());
        verify(tokenManagerService, never()).revokeApiToken(any());
    }
}
