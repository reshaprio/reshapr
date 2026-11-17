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

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.SecretAuthMethod;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.repository.SecretRepository;
import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;

import io.grpc.stub.StreamObserver;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * This is a unit test for the ExpositionDiscoveryServiceHandler class.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ExpositionDiscoveryServiceHandlerTest {

    @Mock
    private ExpositionManagerService expositionManagerService;

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private SecretRepository secretRepository;

    @Mock
    private StreamObserver<ExpositionDiscoveryResponse> responseObserver;

    @Mock
    private StreamObserver<ArtifactsResponse> artifactsResponseObserver;

    private ExpositionDiscoveryServiceHandler handler;

    @BeforeEach
    void setup() {
        handler = new ExpositionDiscoveryServiceHandler(expositionManagerService, artifactRepository, secretRepository);
    }

    @Test
    void testDiscoverExpositions() throws Exception {
        ExpositionDiscoveryRequest request = ExpositionDiscoveryRequest.newBuilder()
                .setGatewayId("gw-1")
                .build();

        Exposition exposition = new Exposition();
        exposition.id = "exp-1";
        
        GatewayGroup group = new GatewayGroup();
        group.name = "group-1";
        exposition.gatewayGroup = group;
        
        ConfigurationPlan plan = new ConfigurationPlan();
        plan.id = "plan-1";
        plan.name = "plan-1";
        plan.backendEndpoint = "http://backend";
        
        Service service = new Service();
        service.id = "srv-1";
        service.name = "srv";
        service.version = "1.0";
        service.organizationId = "org-1";
        service.type = ServiceType.REST;
        plan.service = service;
        exposition.service = service;
        
        exposition.configurationPlan = plan;

        when(expositionManagerService.getGatewayExpositions(eq("gw-1"), any(), any(), nullable(String.class)))
                .thenReturn(List.of(exposition));

        handler.discoverExpositions(request, responseObserver);

        ArgumentCaptor<ExpositionDiscoveryResponse> captor = ArgumentCaptor.forClass(ExpositionDiscoveryResponse.class);
        verify(responseObserver, times(1)).onNext(captor.capture());
        verify(responseObserver, times(1)).onCompleted();

        ExpositionDiscoveryResponse response = captor.getValue();
        assertEquals(1, response.getExpositionsCount());
        assertEquals("exp-1", response.getExpositions(0).getId());
    }

    @Test
    void testDiscoverExpositionsSendsOAuth2StaticAudiences() throws Exception {
        ExpositionDiscoveryRequest request = ExpositionDiscoveryRequest.newBuilder()
              .setGatewayId("gw-1")
              .build();

        Exposition exposition = new Exposition();
        exposition.id = "exp-1";

        GatewayGroup group = new GatewayGroup();
        group.name = "group-1";
        exposition.gatewayGroup = group;

        ConfigurationPlan plan = new ConfigurationPlan();
        plan.id = "plan-1";
        plan.name = "plan-1";
        plan.backendEndpoint = "http://backend";
        plan.oauth2Configuration = new ConfigurationPlan.OAuth2Configuration(
              List.of("https://sso.example.com/realms/test"),
              "https://sso.example.com/realms/test/protocol/openid-connect/certs",
              List.of("profile"),
              List.of("inv-mcp-router", "other-audience"),
              false);

        Service service = new Service();
        service.id = "srv-1";
        service.name = "srv";
        service.version = "1.0";
        service.organizationId = "org-1";
        service.type = ServiceType.REST;
        plan.service = service;
        exposition.service = service;
        exposition.configurationPlan = plan;

        when(expositionManagerService.getGatewayExpositions(eq("gw-1"), any(), any(), nullable(String.class)))
              .thenReturn(List.of(exposition));

        handler.discoverExpositions(request, responseObserver);

        ArgumentCaptor<ExpositionDiscoveryResponse> captor = ArgumentCaptor.forClass(ExpositionDiscoveryResponse.class);
        verify(responseObserver, times(1)).onNext(captor.capture());

        var oauth2 = captor.getValue().getExpositions(0).getConfiguration().getOauth2Configuration();
        assertEquals(List.of("inv-mcp-router", "other-audience"), oauth2.getStaticAudiencesList());
        assertEquals(List.of("profile"), oauth2.getScopesList());
        assertFalse(oauth2.getDisableAudienceValidation());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFetchArtifactsResolvesRemoteSecretsForResourcesAndSkills() throws Exception {
        Artifact resources = new Artifact();
        resources.id = "art-res";
        resources.name = "resources-remote-secret-valid.yaml";
        resources.type = ArtifactType.RESHAPR_RESOURCES;
        resources.mainArtifact = false;
        resources.content = readArtifactFixture("resources-remote-secret-valid.yaml");

        Artifact skills = new Artifact();
        skills.id = "art-skills";
        skills.name = "skills-remote-secret-valid.yaml";
        skills.type = ArtifactType.RESHAPR_SKILLS;
        skills.mainArtifact = false;
        skills.content = readArtifactFixture("skills-remote-secret-valid.yaml");

        Secret docsToken = new Secret();
        docsToken.name = "docs-token";
        docsToken.authMethod = SecretAuthMethod.BEARER_TOKEN;
        docsToken.setToken("tok-123");
        docsToken.tokenHeader = "X-Docs-Token";

        Secret templateToken = new Secret();
        templateToken.name = "template-token";
        templateToken.authMethod = SecretAuthMethod.BEARER_TOKEN;
        templateToken.setToken("tpl-456");

        Secret skillsBasic = new Secret();
        skillsBasic.name = "skills-basic";
        skillsBasic.authMethod = SecretAuthMethod.BASIC;
        skillsBasic.username = "reader";
        skillsBasic.setPassword("secret-pass");

        when(secretRepository.findByName("docs-token")).thenReturn(docsToken);
        when(secretRepository.findByName("template-token")).thenReturn(templateToken);
        when(secretRepository.findByName("skills-basic")).thenReturn(skillsBasic);
        when(secretRepository.findByName("missing-secret")).thenReturn(null);

        PanacheQuery<Artifact> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(resources, skills));
        when(artifactRepository.findByServiceId("srv-1")).thenReturn(query);

        handler.fetchArtifacts(ArtifactsRequest.newBuilder().setServiceId("srv-1").build(), artifactsResponseObserver);

        ArgumentCaptor<ArtifactsResponse> captor = ArgumentCaptor.forClass(ArtifactsResponse.class);
        verify(artifactsResponseObserver).onNext(captor.capture());
        verify(artifactsResponseObserver).onCompleted();

        ArtifactsResponse response = captor.getValue();
        assertEquals(2, response.getArtifactsCount());

        var grpcResources = response.getArtifacts(0);
        // Deduplicated: docs-token appears twice in resources + template-token in resourceTemplates.
        assertEquals(2, grpcResources.getRelatedSecretsCount());
        var resolvedByName = grpcResources.getRelatedSecretsList().stream()
              .collect(java.util.stream.Collectors.toMap(
                    io.reshapr.discovery.exposition.v1.Secret::getName,
                    java.util.function.Function.identity()));
        assertTrue(resolvedByName.containsKey("docs-token"));
        assertEquals("tok-123", resolvedByName.get("docs-token").getToken());
        assertEquals("X-Docs-Token", resolvedByName.get("docs-token").getTokenHeader());
        assertEquals("BEARER_TOKEN", resolvedByName.get("docs-token").getAuthMethod());
        assertTrue(resolvedByName.containsKey("template-token"));
        assertEquals("tpl-456", resolvedByName.get("template-token").getToken());

        var grpcSkills = response.getArtifacts(1);
        // skills-basic resolved; missing-secret logged and omitted without failing.
        assertEquals(1, grpcSkills.getRelatedSecretsCount());
        assertEquals("skills-basic", grpcSkills.getRelatedSecrets(0).getName());
        assertEquals("reader", grpcSkills.getRelatedSecrets(0).getUsername());
        assertEquals("secret-pass", grpcSkills.getRelatedSecrets(0).getPassword());

        verify(secretRepository, times(1)).findByName("docs-token");
        verify(secretRepository, times(1)).findByName("template-token");
        verify(secretRepository, times(1)).findByName("skills-basic");
        verify(secretRepository, times(1)).findByName("missing-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFetchArtifactsByExpositionResolvesRemoteSecrets() throws Exception {
        Service service = new Service();
        service.id = "srv-1";
        ConfigurationPlan plan = new ConfigurationPlan();
        plan.id = "plan-1";
        plan.includedArtifacts = List.of("skills-remote-secret-valid.yaml");
        Exposition exposition = new Exposition();
        exposition.id = "exp-1";
        exposition.service = service;
        exposition.configurationPlan = plan;

        Artifact main = new Artifact();
        main.id = "art-main";
        main.name = "openapi.json";
        main.type = ArtifactType.OPEN_API_SPEC;
        main.mainArtifact = true;
        main.content = readArtifactFixture("openapi.json");

        Artifact skills = new Artifact();
        skills.id = "art-skills";
        skills.name = "skills-remote-secret-valid.yaml";
        skills.type = ArtifactType.RESHAPR_SKILLS;
        skills.mainArtifact = false;
        skills.content = readArtifactFixture("skills-remote-secret-valid.yaml");

        Secret skillsBasic = new Secret();
        skillsBasic.name = "skills-basic";
        skillsBasic.authMethod = SecretAuthMethod.BASIC;
        skillsBasic.username = "reader";
        skillsBasic.setPassword("secret-pass");

        when(expositionManagerService.getExposition("exp-1")).thenReturn(exposition);
        when(secretRepository.findByName("skills-basic")).thenReturn(skillsBasic);
        when(secretRepository.findByName("missing-secret")).thenReturn(null);

        PanacheQuery<Artifact> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(main, skills));
        when(artifactRepository.findByServiceId("srv-1")).thenReturn(query);

        handler.fetchArtifacts(
              ArtifactsRequest.newBuilder().setServiceId("srv-1").setExpositionId("exp-1").build(),
              artifactsResponseObserver);

        ArgumentCaptor<ArtifactsResponse> captor = ArgumentCaptor.forClass(ArtifactsResponse.class);
        verify(artifactsResponseObserver).onNext(captor.capture());
        verify(artifactsResponseObserver).onCompleted();

        ArtifactsResponse response = captor.getValue();
        assertEquals(2, response.getArtifactsCount());
        assertEquals(0, response.getArtifacts(0).getRelatedSecretsCount());
        assertEquals(1, response.getArtifacts(1).getRelatedSecretsCount());
        assertEquals("skills-basic", response.getArtifacts(1).getRelatedSecrets(0).getName());
    }

    private String readArtifactFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/io/reshapr/ctrl/artifacts/" + name)) {
            assertNotNull(in, "Missing test artifact fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
