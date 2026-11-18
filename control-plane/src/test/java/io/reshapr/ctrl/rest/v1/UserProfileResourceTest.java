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
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.UserRepository;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class UserProfileResourceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private Mappers v1Mappers;

    @Mock
    private SecurityIdentity securityIdentity;

    @Mock
    private Principal principal;

    private UserProfileResource userProfileResource;

    @BeforeEach
    void setup() {
        userProfileResource = new UserProfileResource(userRepository, v1Mappers);
    }

    @Test
    void testGetUserProfileSuccess() {
        when(securityIdentity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");

        User user = new User();
        user.firstname = "John";
        user.lastname = "Doe";
        user.username = "testuser";
        user.organizations = java.util.List.of();

        when(userRepository.findByUsername("testuser")).thenReturn(user);

        Response response = userProfileResource.getUserOrganizations(securityIdentity);
        assertEquals(200, response.getStatus());

        UserProfileDTO dto = (UserProfileDTO) response.getEntity();
        assertEquals("John", dto.firstname());
        assertEquals("Doe", dto.lastname());
    }

    @Test
    void testGetUserProfileNotFound() {
        when(securityIdentity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");

        when(userRepository.findByUsername("testuser")).thenReturn(null);

        Response response = userProfileResource.getUserOrganizations(securityIdentity);
        assertEquals(404, response.getStatus());
    }

    @Test
    void testSetDefaultOrganizationNotFound() {
        when(securityIdentity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");

        when(userRepository.findByUsername("testuser")).thenReturn(null);

        Response response = userProfileResource.setDefaultOrganization(securityIdentity, "org-1");
        assertEquals(404, response.getStatus());
    }
}
