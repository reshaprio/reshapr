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

import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.model.UserStatus;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.QuotaRepository;
import io.reshapr.ctrl.repository.UserRepository;
import io.reshapr.ctrl.service.OnboardingService.OrganizationInfo;
import io.reshapr.ctrl.service.OnboardingService.UserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private QuotaRepository quotaRepository;

    private OnboardingService onboardingService;

    @BeforeEach
    void setup() {
        onboardingService = new OnboardingService(userRepository, organizationRepository, quotaRepository);
    }

    @Test
    void testCreateUserSuccess() throws Exception {
        UserInfo userInfo = new UserInfo("jdoe", "jdoe@example.com", "password", "John", "Doe");
        when(userRepository.findByUsername("jdoe")).thenReturn(null);

        User user = onboardingService.createUser(userInfo);

        assertNotNull(user);
        assertEquals("jdoe", user.username);
        assertEquals("jdoe@example.com", user.email);
        assertNotNull(user.password);
        assertEquals("John", user.firstname);
        assertEquals("Doe", user.lastname);
        assertEquals(UserStatus.REGISTERED, user.status);

        verify(userRepository, times(1)).persistAndFlush(user);
    }

    @Test
    void testCreateUserAlreadyExists() {
        UserInfo userInfo = new UserInfo("jdoe", "jdoe@example.com", "password", "John", "Doe");
        User existingUser = new User();
        when(userRepository.findByUsername("jdoe")).thenReturn(existingUser);

        assertThrows(EntityAlreadyExistException.class, () -> onboardingService.createUser(userInfo));
        verify(userRepository, never()).persistAndFlush(any());
    }

    @Test
    void testCreateUserAndAttachToOrganizationSuccess() throws Exception {
        UserInfo userInfo = new UserInfo("jdoe", "jdoe@example.com", "password", "John", "Doe");
        Organization org = new Organization();
        org.name = "org-1";

        when(organizationRepository.findByName("org-1")).thenReturn(org);
        when(userRepository.findByUsername("jdoe")).thenReturn(null);

        User user = onboardingService.createUserAndAttachToOrganization(userInfo, "org-1");

        assertNotNull(user);
        assertEquals(1, user.organizations.size());
        assertEquals(org, user.organizations.get(0));
        assertEquals(org, user.defaultOrganization);

        verify(userRepository, times(2)).persistAndFlush(user); // Once for create, once for attach
    }

    @Test
    void testCreateOrganizationSuccess() throws Exception {
        OrganizationInfo orgInfo = new OrganizationInfo("org-1", "Description", "icon-url");
        User user = new User();
        user.username = "jdoe";

        when(userRepository.findByUsername("jdoe")).thenReturn(user);
        when(organizationRepository.findByName("org-1")).thenReturn(null);

        Organization org = onboardingService.createOrganization("jdoe", orgInfo);

        assertNotNull(org);
        assertEquals("org-1", org.name);
        assertEquals("Description", org.description);
        assertEquals("icon-url", org.icon);
        assertEquals(user, org.owner);

        verify(organizationRepository, times(1)).persistAndFlush(org);
        assertEquals(1, user.organizations.size());
        assertEquals(org, user.defaultOrganization);
        verify(userRepository, times(1)).persistAndFlush(user);
    }

    @Test
    void testCreateOrganizationAlreadyExists() {
        OrganizationInfo orgInfo = new OrganizationInfo("org-1", "Description", "icon-url");
        User user = new User();
        
        when(userRepository.findByUsername("jdoe")).thenReturn(user);
        when(organizationRepository.findByName("org-1")).thenReturn(new Organization());

        assertThrows(EntityAlreadyExistException.class, () -> onboardingService.createOrganization("jdoe", orgInfo));
    }

    @Test
    void testCreateOrganizationUserNotFound() {
        OrganizationInfo orgInfo = new OrganizationInfo("org-1", "Description", "icon-url");
        when(userRepository.findByUsername("jdoe")).thenReturn(null);

        assertThrows(DependencyNotFoundException.class, () -> onboardingService.createOrganization("jdoe", orgInfo));
    }
}
