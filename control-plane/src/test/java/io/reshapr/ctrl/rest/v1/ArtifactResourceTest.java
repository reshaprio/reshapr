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

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.service.ArtifactManagerService;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.ServiceManagerService;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ArtifactResourceTest {

    @Mock
    private ServiceManagerService serviceManagerService;

    @Mock
    private ArtifactManagerService artifactManagerService;

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private Mappers v1Mappers;

    @Mock
    private PanacheQuery<Artifact> mockQuery;

    @Mock
    private PanacheQuery<ArtifactDTO> mockDtoQuery;

    @Mock
    private PanacheQuery<ArtifactReferenceDTO> mockRefDtoQuery;

    private ArtifactResource artifactResource;

    @BeforeEach
    void setup() {
        artifactResource = new ArtifactResource(serviceManagerService, artifactManagerService, artifactRepository, v1Mappers);
    }

    @Test
    void testGetArtifactSuccess() {
        Artifact artifact = new Artifact();
        ArtifactDTO dto = mock(ArtifactDTO.class);
        
        when(artifactRepository.findById("art-1")).thenReturn(artifact);
        when(v1Mappers.toResource(artifact)).thenReturn(dto);

        Response response = artifactResource.getArtifact("art-1");
        assertEquals(200, response.getStatus());
        assertEquals(dto, response.getEntity());
    }

    @Test
    void testGetArtifactNotFound() {
        when(artifactRepository.findById("art-1")).thenReturn(null);

        Response response = artifactResource.getArtifact("art-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testGetArtifactsByServiceId() {
        ArtifactDTO dto = mock(ArtifactDTO.class);
        
        when(artifactRepository.findByServiceId("svc-1")).thenReturn(mockQuery);
        when(mockQuery.project(ArtifactDTO.class)).thenReturn(mockDtoQuery);
        when(mockDtoQuery.list()).thenReturn(List.of(dto));

        List<ArtifactDTO> result = artifactResource.getArtifactsByServiceId("svc-1");
        assertEquals(1, result.size());
    }

    @Test
    void testGetArtifactReferencesByServiceId() {
        ArtifactReferenceDTO dto = mock(ArtifactReferenceDTO.class);
        
        when(artifactRepository.findByServiceId("svc-1")).thenReturn(mockQuery);
        when(mockQuery.project(ArtifactReferenceDTO.class)).thenReturn(mockRefDtoQuery);
        when(mockRefDtoQuery.list()).thenReturn(List.of(dto));

        List<ArtifactReferenceDTO> result = artifactResource.getArtifactReferencesByServiceId("svc-1");
        assertEquals(1, result.size());
    }

    @Test
    void testGetArtifactDeletionImpactSuccess() throws Exception {
        io.reshapr.ctrl.service.ArtifactDeletionImpact impact = mock(io.reshapr.ctrl.service.ArtifactDeletionImpact.class);
        ArtifactDeletionImpactDTO dto = mock(ArtifactDeletionImpactDTO.class);

        when(artifactManagerService.getArtifactDeletionImpact("art-1")).thenReturn(impact);
        when(v1Mappers.toResource(impact)).thenReturn(dto);

        Response response = artifactResource.getArtifactDeletionImpact("art-1");
        assertEquals(200, response.getStatus());
        assertEquals(dto, response.getEntity());
    }

    @Test
    void testGetArtifactDeletionImpactNotFound() throws Exception {
        when(artifactManagerService.getArtifactDeletionImpact("art-1")).thenThrow(new DependencyNotFoundException("Not found"));

        Response response = artifactResource.getArtifactDeletionImpact("art-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testDeleteArtifactSuccess() throws Exception {
        io.reshapr.ctrl.service.ArtifactDeletionImpact impact = mock(io.reshapr.ctrl.service.ArtifactDeletionImpact.class);
        ArtifactDeletionImpactDTO dto = mock(ArtifactDeletionImpactDTO.class);

        when(artifactManagerService.deleteArtifact("art-1")).thenReturn(impact);
        when(v1Mappers.toResource(impact)).thenReturn(dto);

        Response response = artifactResource.deleteArtifact("art-1");
        assertEquals(200, response.getStatus());
        assertEquals(dto, response.getEntity());
    }

    @Test
    void testDeleteArtifactNotFound() throws Exception {
        when(artifactManagerService.deleteArtifact("art-1")).thenThrow(new DependencyNotFoundException("Not found"));

        Response response = artifactResource.deleteArtifact("art-1");
        assertEquals(404, response.getStatus());
    }
}
