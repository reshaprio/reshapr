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
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.SecretType;
import io.reshapr.ctrl.repository.SecretRepository;

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
class SecretResourceTest {

    @Mock
    private SecretRepository secretRepository;

    @Mock
    private Mappers v1Mappers;

    @Mock
    private MappersImpl mappersImpl;

    @Mock
    private PanacheQuery<Secret> mockQuery;

    @Mock
    private PanacheQuery<SecretReferenceDTO> mockRefQuery;

    private SecretResource secretResource;

    @BeforeEach
    void setup() {
        secretResource = new SecretResource(secretRepository, v1Mappers, mappersImpl);
    }

    @Test
    void testGetSecrets() {
        Secret secret = new Secret();
        SecretDTO dto = mock(SecretDTO.class);
        when(dto.id()).thenReturn("sec-1");

        when(secretRepository.findAll(any(Sort.class))).thenReturn(mockQuery);
        when(mockQuery.page(any(Page.class))).thenReturn(mockQuery);
        when(mockQuery.list()).thenReturn(List.of(secret));
        when(v1Mappers.toResource(secret)).thenReturn(dto);

        List<SecretDTO> result = secretResource.getSecrets(0, 20);
        assertEquals(1, result.size());
        assertEquals("sec-1", result.get(0).id());
    }

    @Test
    void testGetSecretReferences() {
        SecretReferenceDTO dto = mock(SecretReferenceDTO.class);
        when(dto.id()).thenReturn("sec-1");

        when(secretRepository.findAll(any(Sort.class))).thenReturn(mockQuery);
        when(mockQuery.page(any(Page.class))).thenReturn(mockQuery);
        when(mockQuery.project(SecretReferenceDTO.class)).thenReturn(mockRefQuery);
        when(mockRefQuery.list()).thenReturn(List.of(dto));

        List<SecretReferenceDTO> result = secretResource.getSecretReferences(0, 20);
        assertEquals(1, result.size());
        assertEquals("sec-1", result.get(0).id());
    }

    @Test
    void testCreateSecretSuccess() {
        SecretDTO requestDto = mock(SecretDTO.class);
        when(requestDto.name()).thenReturn("New Secret");
        
        Secret secret = new Secret();
        SecretDTO responseDto = mock(SecretDTO.class);
        
        when(secretRepository.findByName("New Secret")).thenReturn(null);
        when(v1Mappers.fromResource(requestDto)).thenReturn(secret);
        doNothing().when(secretRepository).persistAndFlush(secret);
        when(v1Mappers.toResource(secret)).thenReturn(responseDto);

        Response response = secretResource.createSecret(requestDto);
        assertEquals(201, response.getStatus());
        assertEquals(responseDto, response.getEntity());
    }

    @Test
    void testCreateSecretConflict() {
        SecretDTO requestDto = mock(SecretDTO.class);
        when(requestDto.name()).thenReturn("Existing Secret");
        when(secretRepository.findByName("Existing Secret")).thenReturn(new Secret());

        Response response = secretResource.createSecret(requestDto);
        assertEquals(409, response.getStatus());
    }

    @Test
    void testUpdateSecretSuccess() {
        SecretDTO requestDto = mock(SecretDTO.class);
        when(requestDto.password()).thenReturn("new-password");
        when(requestDto.token()).thenReturn("new-token");
        Secret existingSecret = new Secret();

        when(secretRepository.findById("sec-1")).thenReturn(existingSecret);
        when(v1Mappers.toResource(existingSecret)).thenReturn(requestDto);

        Response response = secretResource.updateSecret("sec-1", requestDto);
        assertEquals(200, response.getStatus());
        assertEquals(requestDto, response.getEntity());
    }

    @Test
    void testDeleteSecretSuccess() {
        Secret secret = new Secret();
        when(secretRepository.findById("sec-1")).thenReturn(secret);
        doNothing().when(secretRepository).delete(secret);

        Response response = secretResource.deleteSecret("sec-1");
        assertEquals(204, response.getStatus());
    }

    @Test
    void testDeleteSecretNotFound() {
        when(secretRepository.findById("sec-1")).thenReturn(null);

        Response response = secretResource.deleteSecret("sec-1");
        assertEquals(404, response.getStatus());
    }
}
