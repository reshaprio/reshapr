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

import io.reshapr.ctrl.model.ActiveExposition;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.repository.ExpositionRepository;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.ExpositionManagerService;

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
class ExpositionResourceTest {

    @Mock
    private ExpositionManagerService expositionManagerService;

    @Mock
    private ExpositionRepository expositionRepository;

    @Mock
    private Mappers v1Mappers;

    private ExpositionResource expositionResource;

    @BeforeEach
    void setup() {
        expositionResource = new ExpositionResource(expositionManagerService, expositionRepository, v1Mappers);
    }

    @Test
    void testCreateExpositionSuccess() throws Exception {
        ExpositionReferenceDTO requestDto = new ExpositionReferenceDTO(null, null, "My Expo", null, "gg-1", "plan-1");
        Exposition exposition = new Exposition();
        ExpositionDTO responseDto = new ExpositionDTO("exp-1", "org-1", "My Expo", null, null, null, null);

        when(expositionManagerService.exposeConfiguration("plan-1", "gg-1", "My Expo")).thenReturn(exposition);
        when(v1Mappers.toResource(exposition)).thenReturn(responseDto);

        Response response = expositionResource.createExposition(requestDto);
        assertEquals(201, response.getStatus());
        assertEquals(responseDto, response.getEntity());
    }

    @Test
    void testCreateExpositionNotFound() throws Exception {
        ExpositionReferenceDTO requestDto = new ExpositionReferenceDTO(null, null, "My Expo", null, "gg-1", "plan-1");

        when(expositionManagerService.exposeConfiguration("plan-1", "gg-1", "My Expo"))
                .thenThrow(new DependencyNotFoundException("Plan not found"));

        Response response = expositionResource.createExposition(requestDto);
        assertEquals(404, response.getStatus());
    }

    @Test
    void testListExpositions() {
        Exposition exposition = new Exposition();
        ExpositionDTO dto = new ExpositionDTO("exp-1", "org-1", "My Expo", null, null, null, null);

        when(expositionManagerService.getExpositions("svc-1", "gg-1")).thenReturn(List.of(exposition));
        when(v1Mappers.toEResources(List.of(exposition))).thenReturn(List.of(dto));

        List<ExpositionDTO> result = expositionResource.listExpositions("svc-1", "gg-1");
        assertEquals(1, result.size());
        assertEquals("exp-1", result.get(0).id());
    }

    @Test
    void testListActiveExpositions() {
        ActiveExposition activeExposition = new ActiveExposition();
        ActiveExpositionDTO dto = new ActiveExpositionDTO("act-1", "org-1", "My Expo", null, null, null, null);

        when(expositionManagerService.getActiveExpositions()).thenReturn(List.of(activeExposition));
        when(v1Mappers.toAEResources(List.of(activeExposition))).thenReturn(List.of(dto));

        List<ActiveExpositionDTO> result = expositionResource.listActiveExpositions();
        assertEquals(1, result.size());
        assertEquals("act-1", result.get(0).id());
    }

    @Test
    void testGetExpositionSuccess() {
        Exposition exposition = new Exposition();
        ExpositionDTO dto = new ExpositionDTO("exp-1", "org-1", "My Expo", null, null, null, null);

        when(expositionManagerService.getExposition("exp-1")).thenReturn(exposition);
        when(v1Mappers.toResource(exposition)).thenReturn(dto);

        Response response = expositionResource.getExposition("exp-1");
        assertEquals(200, response.getStatus());
        assertEquals(dto, response.getEntity());
    }

    @Test
    void testGetExpositionNotFound() {
        when(expositionManagerService.getExposition("exp-1")).thenReturn(null);

        Response response = expositionResource.getExposition("exp-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testGetActiveExpositionSuccess() {
        ActiveExposition activeExposition = new ActiveExposition();
        ActiveExpositionDTO dto = new ActiveExpositionDTO("act-1", "org-1", "My Expo", null, null, null, null);

        when(expositionManagerService.getActiveExposition("act-1")).thenReturn(activeExposition);
        when(v1Mappers.toResource(activeExposition)).thenReturn(dto);

        Response response = expositionResource.getActiveExposition("act-1");
        assertEquals(200, response.getStatus());
        assertEquals(dto, response.getEntity());
    }

    @Test
    void testGetActiveExpositionNotFound() {
        when(expositionManagerService.getActiveExposition("act-1")).thenReturn(null);

        Response response = expositionResource.getActiveExposition("act-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testDeleteExpositionSuccess() throws Exception {
        doNothing().when(expositionManagerService).removeExposition("exp-1");

        Response response = expositionResource.deleteExposition("exp-1");
        assertEquals(204, response.getStatus());
    }

    @Test
    void testDeleteExpositionNotFound() throws Exception {
        doThrow(new DependencyNotFoundException("Not found")).when(expositionManagerService).removeExposition("exp-1");

        Response response = expositionResource.deleteExposition("exp-1");
        assertEquals(404, response.getStatus());
    }
}
