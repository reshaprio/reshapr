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

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ServiceRepository;
import io.reshapr.ctrl.service.ServiceManagerService;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ServiceResourceTest {

    @Mock
    private ServiceManagerService serviceManagerService;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private Mappers v1Mappers;

    @Mock
    private PanacheQuery<Service> mockQuery;

    @Mock
    private PanacheQuery<ServiceDTO> mockDtoQuery;

    private ServiceResource serviceResource;

    @BeforeEach
    void setup() {
        serviceResource = new ServiceResource(serviceManagerService, serviceRepository, v1Mappers);
    }

    @Test
    void testGetServices() {
        ServiceDTO dto = mock(ServiceDTO.class);
        when(dto.id()).thenReturn("svc-1");

        when(serviceRepository.findAll(any(Sort.class))).thenReturn(mockQuery);
        when(mockQuery.page(any(Page.class))).thenReturn(mockQuery);
        when(mockQuery.project(ServiceDTO.class)).thenReturn(mockDtoQuery);
        when(mockDtoQuery.list()).thenReturn(List.of(dto));

        List<ServiceDTO> result = serviceResource.getServices(0, 20);
        assertEquals(1, result.size());
        assertEquals("svc-1", result.get(0).id());
    }

    @Test
    void testGetServiceViewSuccess() {
        Service service = new Service();
        ServiceViewDTO viewDto = mock(ServiceViewDTO.class);

        when(serviceRepository.findByIdWithOperations("svc-1")).thenReturn(service);
        when(v1Mappers.toResource(service)).thenReturn(viewDto);

        Response response = serviceResource.getServiceView("svc-1");
        assertEquals(200, response.getStatus());
        assertEquals(viewDto, response.getEntity());
    }

    @Test
    void testGetServiceViewNotFound() {
        when(serviceRepository.findByIdWithOperations("svc-1")).thenReturn(null);

        Response response = serviceResource.getServiceView("svc-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testDeleteServiceSuccess() {
        when(serviceManagerService.deleteService("svc-1")).thenReturn(true);

        Response response = serviceResource.deleteService("svc-1");
        assertEquals(204, response.getStatus());
    }

    @Test
    void testDeleteServiceNotFound() {
        when(serviceManagerService.deleteService("svc-1")).thenReturn(false);

        Response response = serviceResource.deleteService("svc-1");
        assertEquals(404, response.getStatus());
    }
}
