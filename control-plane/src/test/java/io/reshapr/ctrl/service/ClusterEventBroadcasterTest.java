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

import io.reshapr.ctrl.event.ClusterEvents;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClusterEventBroadcaster.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ClusterEventBroadcasterTest {

    @Mock
    HazelcastInstance hazelcast;

    @Mock
    ExpositionDiscoveryServiceHandler expositionDiscoveryServiceHandler;

    @Mock
    ITopic<ClusterEvents.ExpositionGatewayGroupEvent> expositionChangesTopic;

    @InjectMocks
    ClusterEventBroadcaster clusterEventBroadcaster;

    @BeforeEach
    void setUp() {
        when(hazelcast.<ClusterEvents.ExpositionGatewayGroupEvent>getTopic("exposition-changes")).thenReturn(expositionChangesTopic);
        clusterEventBroadcaster.onStart(new StartupEvent());
    }

    @Test
    void testPublishExpositionCreationEvent() {
        Exposition exposition = new Exposition();
        exposition.id = "exp-1";
        GatewayGroup gatewayGroup = new GatewayGroup();
        gatewayGroup.id = "gg-1";

        clusterEventBroadcaster.publishExpositionCreationEvent(exposition, gatewayGroup);

        ArgumentCaptor<ClusterEvents.ExpositionGatewayGroupCreationEvent> eventCaptor = 
                ArgumentCaptor.forClass(ClusterEvents.ExpositionGatewayGroupCreationEvent.class);
        verify(expositionChangesTopic).publish(eventCaptor.capture());

        ClusterEvents.ExpositionGatewayGroupCreationEvent event = eventCaptor.getValue();
        assertEquals("exp-1", event.exposition().id);
        assertEquals("gg-1", event.gatewayGroup().id);
    }

    @Test
    void testPublishExpositionUpdateEvent() {
        Exposition exposition = new Exposition();
        exposition.id = "exp-1";
        GatewayGroup gatewayGroup = new GatewayGroup();
        gatewayGroup.id = "gg-1";

        clusterEventBroadcaster.publishExpositionUpdateEvent(exposition, gatewayGroup);

        ArgumentCaptor<ClusterEvents.ExpositionGatewayGroupUpdateEvent> eventCaptor = 
                ArgumentCaptor.forClass(ClusterEvents.ExpositionGatewayGroupUpdateEvent.class);
        verify(expositionChangesTopic).publish(eventCaptor.capture());

        ClusterEvents.ExpositionGatewayGroupUpdateEvent event = eventCaptor.getValue();
        assertEquals("exp-1", event.exposition().id);
        assertEquals("gg-1", event.gatewayGroup().id);
    }

    @Test
    void testPublishExpositionDeletionEvent() {
        Exposition exposition = new Exposition();
        exposition.id = "exp-1";
        GatewayGroup gatewayGroup = new GatewayGroup();
        gatewayGroup.id = "gg-1";

        clusterEventBroadcaster.publishExpositionDeletionEvent(exposition, gatewayGroup);

        ArgumentCaptor<ClusterEvents.ExpositionGatewayGroupDeletionEvent> eventCaptor = 
                ArgumentCaptor.forClass(ClusterEvents.ExpositionGatewayGroupDeletionEvent.class);
        verify(expositionChangesTopic).publish(eventCaptor.capture());

        ClusterEvents.ExpositionGatewayGroupDeletionEvent event = eventCaptor.getValue();
        assertEquals("exp-1", event.exposition().id);
        assertEquals("gg-1", event.gatewayGroup().id);
    }
}
