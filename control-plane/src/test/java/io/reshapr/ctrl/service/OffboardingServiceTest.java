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
import io.reshapr.ctrl.repository.ApiTokenRepository;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.repository.GatewayRepository;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.QuotaRepository;
import io.reshapr.ctrl.repository.SecretRepository;
import io.reshapr.ctrl.repository.ServiceRepository;
import io.reshapr.ctrl.repository.UserRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class OffboardingServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ServiceManagerService serviceManagerService;

    @Mock
    private SecretRepository secretRepository;

    @Mock
    private GatewayRepository gatewayRepository;

    @Mock
    private GatewayGroupRepository gatewayGroupRepository;

    @Mock
    private QuotaRepository quotaRepository;

    @Mock
    private ApiTokenRepository apiTokenRepository;

    private OffboardingService offboardingService;
    private MockedStatic<ReshaprTenantContext> mockedContext;

    @BeforeEach
    void setup() {
        offboardingService = new OffboardingService(
                organizationRepository, userRepository, serviceRepository,
                serviceManagerService, secretRepository, gatewayRepository,
                gatewayGroupRepository, quotaRepository, apiTokenRepository
        );
        mockedContext = mockStatic(ReshaprTenantContext.class);
    }

    @AfterEach
    void teardown() {
        mockedContext.close();
    }

    @Test
    void testDeleteOrganizationCannotDeleteRoot() {
        assertThrows(IllegalStateException.class, () -> offboardingService.deleteOrganization("reshapr"));
    }

    @Test
    void testDeleteOrganizationNotFound() {
        when(organizationRepository.findByName("org-1")).thenReturn(null);
        assertThrows(DependencyNotFoundException.class, () -> offboardingService.deleteOrganization("org-1"));
    }

    @Test
    void testDeleteUserNotFound() {
        when(userRepository.findByUsername("user-1")).thenReturn(null);
        assertThrows(DependencyNotFoundException.class, () -> offboardingService.deleteUser("user-1"));
    }

    @Test
    void testDeleteUserCannotDeleteRootOwner() {
        User user = new User();
        user.username = "root-owner";

        Organization rootOrg = new Organization();
        rootOrg.owner = user;

        when(userRepository.findByUsername("root-owner")).thenReturn(user);
        when(organizationRepository.findByName("reshapr")).thenReturn(rootOrg);

        assertThrows(IllegalStateException.class, () -> offboardingService.deleteUser("root-owner"));
    }
}
