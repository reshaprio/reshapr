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

import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.ApiTokenRepository;
import io.reshapr.ctrl.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class TokenManagerServiceTest {

    @Mock
    private ApiTokenRepository apiTokenRepository;

    @Mock
    private UserRepository userRepository;

    private TokenManagerService tokenManagerService;

    @BeforeEach
    void setup() {
        tokenManagerService = new TokenManagerService(apiTokenRepository, userRepository);
    }

    @Test
    void testGetApiTokens() {
        ApiToken token = new ApiToken();
        when(apiTokenRepository.findByOrganizationId("org-1")).thenReturn(List.of(token));

        List<ApiToken> result = tokenManagerService.getApiTokens("org-1");
        assertEquals(1, result.size());
    }

    @Test
    void testGenerateApiTokenSuccess() throws Exception {
        User user = new User();
        user.username = "testuser";
        when(userRepository.findByUsername("testuser")).thenReturn(user);

        ApiToken token = tokenManagerService.generateApiToken("My Token", "org-1", 30, "testuser");
        
        assertNotNull(token);
        assertEquals("My Token", token.name);
        assertEquals("org-1", token.organizationId);
        assertEquals(user, token.user);
        assertNotNull(token.token);
        assertNotNull(token.validUntil);

        verify(apiTokenRepository, times(1)).persist(token);
    }

    @Test
    void testGenerateApiTokenUserNotFound() {
        when(userRepository.findByUsername("testuser")).thenReturn(null);

        assertThrows(DependencyNotFoundException.class, () -> {
            tokenManagerService.generateApiToken("My Token", "org-1", 30, "testuser");
        });
    }

    @Test
    void testRevokeApiToken() {
        ApiToken token = new ApiToken();
        tokenManagerService.revokeApiToken(token);
        verify(apiTokenRepository, times(1)).delete(token);
    }
}
