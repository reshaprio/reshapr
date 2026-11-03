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
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that exposition events are only published to the cluster once the transaction is committed.
 * @author laurent
 */
@ExtendWith(MockitoExtension.class)
class ClusterEventBroadcasterTest {

   @Mock
   private HazelcastInstance hazelcast;

   @Mock
   private ExpositionDiscoveryServiceHandler discoveryHandler;

   @Mock
   private TransactionSynchronizationRegistry txRegistry;

   @Mock
   private ITopic<ClusterEvents.ExpositionGatewayGroupEvent> topic;

   private ClusterEventBroadcaster broadcaster;
   private Exposition exposition;
   private GatewayGroup gatewayGroup;

   @BeforeEach
   @SuppressWarnings("unchecked")
   void setup() {
      when(hazelcast.<ClusterEvents.ExpositionGatewayGroupEvent>getTopic("exposition-changes")).thenReturn(topic);
      broadcaster = new ClusterEventBroadcaster(hazelcast, discoveryHandler, txRegistry);
      broadcaster.onStart(null);
      exposition = new Exposition();
      exposition.id = "expo-1";
      gatewayGroup = new GatewayGroup();
   }

   @Test
   void publishesImmediatelyWhenNoTransactionIsActive() {
      when(txRegistry.getTransactionStatus()).thenReturn(Status.STATUS_NO_TRANSACTION);

      broadcaster.publishExpositionCreationEvent(exposition, gatewayGroup);

      ArgumentCaptor<ClusterEvents.ExpositionGatewayGroupEvent> captor = ArgumentCaptor.forClass(ClusterEvents.ExpositionGatewayGroupEvent.class);
      verify(topic).publish(captor.capture());
      assertInstanceOf(ClusterEvents.ExpositionGatewayGroupCreationEvent.class, captor.getValue());
      assertSame(exposition, captor.getValue().exposition());
   }

   @Test
   void defersPublicationUntilCommit() {
      Synchronization sync = registerSynchronizationOnCreation();

      verify(topic, never()).publish(any());

      sync.afterCompletion(Status.STATUS_COMMITTED);

      verify(topic).publish(any(ClusterEvents.ExpositionGatewayGroupCreationEvent.class));
   }

   @Test
   void doesNotPublishOnRollback() {
      Synchronization sync = registerSynchronizationOnCreation();

      sync.afterCompletion(Status.STATUS_ROLLEDBACK);

      verify(topic, never()).publish(any());
   }

   @Test
   void updateAndDeletionEventsAreAlsoDeferredUntilCommit() {
      when(txRegistry.getTransactionStatus()).thenReturn(Status.STATUS_ACTIVE);

      broadcaster.publishExpositionUpdateEvent(exposition, gatewayGroup);
      broadcaster.publishExpositionDeletionEvent(exposition, gatewayGroup);

      ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);
      verify(txRegistry, times(2)).registerInterposedSynchronization(captor.capture());
      verify(topic, never()).publish(any());

      captor.getAllValues().forEach(sync -> sync.afterCompletion(Status.STATUS_COMMITTED));

      verify(topic).publish(any(ClusterEvents.ExpositionGatewayGroupUpdateEvent.class));
      verify(topic).publish(any(ClusterEvents.ExpositionGatewayGroupDeletionEvent.class));
   }

   private Synchronization registerSynchronizationOnCreation() {
      when(txRegistry.getTransactionStatus()).thenReturn(Status.STATUS_ACTIVE);

      broadcaster.publishExpositionCreationEvent(exposition, gatewayGroup);

      ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);
      verify(txRegistry).registerInterposedSynchronization(captor.capture());
      return captor.getValue();
   }
}
