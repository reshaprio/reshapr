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
import io.quarkus.grpc.GrpcService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

/**
 * A service that broadcasts events to the cluster when an exposition is created, updated or deleted. It also
 * asynchronously propagates those events to the local {@link ExpositionDiscoveryServiceHandler} so that the
 * gateways can be updated accordingly.
 * @author laurent
 */
@ApplicationScoped
public class ClusterEventBroadcaster {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final HazelcastInstance hazelcast;
   private final ExpositionDiscoveryServiceHandler expositionDiscoveryServiceHandler;
   private final TransactionSynchronizationRegistry txRegistry;

   private ITopic<ClusterEvents.ExpositionGatewayGroupEvent> expositionChangesTopic;

   public ClusterEventBroadcaster(HazelcastInstance hazelcast,
                                  @GrpcService ExpositionDiscoveryServiceHandler expositionDiscoveryServiceHandler,
                                  TransactionSynchronizationRegistry txRegistry) {
      this.hazelcast = hazelcast;
      this.expositionDiscoveryServiceHandler = expositionDiscoveryServiceHandler;
      this.txRegistry = txRegistry;
   }

   /**
    * Runs the action once the current transaction is successfully committed, so that receivers fetching
    * the data can see it. Runs immediately when no transaction is active; skipped on rollback.
    */
   private void afterCommit(Runnable action) {
      if (txRegistry.getTransactionStatus() != Status.STATUS_ACTIVE) {
         action.run();
         return;
      }
      txRegistry.registerInterposedSynchronization(new Synchronization() {
         @Override
         public void beforeCompletion() {
            // Nothing to do before completion.
         }

         @Override
         public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
               action.run();
            }
         }
      });
   }

   /** Application startup method. */
   void onStart(@Observes StartupEvent ev) {
      expositionChangesTopic = hazelcast.getTopic("exposition-changes");
      expositionChangesTopic.addMessageListener(
            message -> receiveExpositionChangeEvent(message.getMessageObject())
      );
      logger.info("ClusterEventBroadcaster started and ready to publish/receive events.");
   }

   /**
    * Publishes an exposition creation event to the cluster.
    * @param exposition The exposition that was created.
    * @param gatewayGroup The gateway group associated with the exposition creation event.
    */
   public void publishExpositionCreationEvent(Exposition exposition, GatewayGroup gatewayGroup) {
      afterCommit(() -> {
         logger.infof("Publishing exposition creation for exposition '%s'", exposition.id);
         expositionChangesTopic.publish(new ClusterEvents.ExpositionGatewayGroupCreationEvent(exposition, gatewayGroup));
      });
   }

   /**
    * Publishes an exposition update event to the cluster.
    * @param exposition The exposition that was updated.
    * @param gatewayGroup The gateway group associated with the exposition update event.
    */
   public void publishExpositionUpdateEvent(Exposition exposition, GatewayGroup gatewayGroup) {
      afterCommit(() -> {
         logger.infof("Publishing exposition change for exposition '%s'", exposition.id);
         expositionChangesTopic.publish(new ClusterEvents.ExpositionGatewayGroupUpdateEvent(exposition, gatewayGroup));
      });
   }

   /**
    * Publishes an exposition deletion event to the cluster.
    * @param exposition The exposition that was deleted.
    * @param gatewayGroup The gateway group associated with the exposition deletion event.
    */
   public void publishExpositionDeletionEvent(Exposition exposition, GatewayGroup gatewayGroup) {
      afterCommit(() -> {
         logger.infof("Publishing exposition deletion for exposition '%s'", exposition.id);
         expositionChangesTopic.publish(new ClusterEvents.ExpositionGatewayGroupDeletionEvent(exposition, gatewayGroup));
      });
   }

   private void receiveExpositionChangeEvent(ClusterEvents.ExpositionGatewayGroupEvent event) {
      logger.infof("Received exposition change event: %s for exposition '%s'", event.getClass().getName(), event.exposition().id);

      switch (event) {
         case ClusterEvents.ExpositionGatewayGroupCreationEvent(Exposition exposition, GatewayGroup gatewayGroup) ->
            expositionDiscoveryServiceHandler.notifyExpositionCreation(exposition, gatewayGroup);
         case ClusterEvents.ExpositionGatewayGroupUpdateEvent(Exposition exposition, GatewayGroup gatewayGroup) ->
            expositionDiscoveryServiceHandler.notifyExpositionUpdate(exposition, gatewayGroup);
         case ClusterEvents.ExpositionGatewayGroupDeletionEvent(Exposition exposition, GatewayGroup gatewayGroup) ->
            expositionDiscoveryServiceHandler.notifyExpositionDeletion(exposition, gatewayGroup);
      }
   }
}
