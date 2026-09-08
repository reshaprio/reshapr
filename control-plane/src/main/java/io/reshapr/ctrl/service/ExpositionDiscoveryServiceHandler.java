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

import io.reshapr.ctrl.control.QuotaExceededException;
import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;
import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.ChangeType;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc;
import io.reshapr.discovery.exposition.v1.ExpositionFetchRequest;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.security.Authenticated;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * gRPC service handler for exposition discovery requests.
 *
 * @author laurent
 */
@GrpcService
public class ExpositionDiscoveryServiceHandler extends ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceImplBase {

   /**
    * Get a JBoss logging logger.
    */
   private final Logger logger = Logger.getLogger(getClass());

   private final Executor cancellationExecutor = Executors.newSingleThreadExecutor();

   /**
    * Map of organization ID to list of observers for exposition changes.
    * Why ConcurrentHashMap? See https://www.baeldung.com/java-synchronizedmap-vs-concurrenthashmap.
    */
   private final Map<String, List<OrganizationExpositionsObserver>> expositionChangeObservers = new ConcurrentHashMap<>();

   private final ExpositionManagerService expositionManagerService;
   private final ArtifactRepository artifactRepository;

   /**
    * @param expositionManagerService
    * @param artifactRepository
    */
   public ExpositionDiscoveryServiceHandler(ExpositionManagerService expositionManagerService,
                                            ArtifactRepository artifactRepository) {
      this.expositionManagerService = expositionManagerService;
      this.artifactRepository = artifactRepository;

   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void discoverExpositions(ExpositionDiscoveryRequest request, StreamObserver<ExpositionDiscoveryResponse> responseObserver) {
      logger.infof("Received ExpositionDiscoveryRequest for gatewayId: %s", request.getGatewayId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      ExpositionDiscoveryResponse.Builder builder = ExpositionDiscoveryResponse.newBuilder();

      List<Exposition> expositions;
      try {
         expositions = expositionManagerService.getGatewayExpositions(request.getGatewayId(),
               request.getLabelsMap(), request.getFqdnsList(), request.hasVersion() ? request.getVersion() : null);
      } catch (QuotaExceededException e) {
         // Translate the transport-agnostic business exception into the relevant gRPC status.
         logger.warnf("Rejecting gateway registration for gatewayId '%s': %s", request.getGatewayId(), e.getMessage());
         responseObserver.onError(Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage()).asRuntimeException());
         return;
      }
      for (Exposition exposition : expositions) {
         builder.addExpositions(grpcExpositionFromModel(exposition));
      }

      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void streamExpositionChanges(ExpositionDiscoveryRequest request, StreamObserver<ExpositionChangeEvent> responseObserver) {
      logger.infof("==> Subscribing to ExpositionChanges for gatewayId: %s", request.getGatewayId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());
      logger.tracef("Current tenant is '%s'", ReshaprTenantContext.getCurrentTenant());

      String organization = ReshaprTenantContext.getCurrentTenant();

      // Register the observer for the given tenant with gatewayId and labels.
      OrganizationExpositionsObserver observer = new OrganizationExpositionsObserver(request.getGatewayId(),
            request.getLabelsMap(), responseObserver);
      addOrganizationExpositionsObserver(organization, observer);

      List<OrganizationExpositionsObserver> observers = expositionChangeObservers.get(organization);
      logger.infof("# Current number of observers for organization '%s': %d", organization, observers.size());

      // Set a cancellation handler to remove the observer when the stream is cancelled.
      var serverCallStreamObserver = ((ServerCallStreamObserver<ExpositionChangeEvent>) responseObserver);
      serverCallStreamObserver.setOnCancelHandler(() -> {
         logger.infof("OnCancelHandler triggered for gatewayId: %s", request.getGatewayId());
         removeOrganizationExpositionsObserver(organization, request.getGatewayId());
      });

      // Set a Cancellation listener to remove the observer when the context is cancelled.
      Context.current().addListener(listener -> {
         ;
         logger.infof("Cancellation listener triggered for gatewayId: %s", request.getGatewayId());
         removeOrganizationExpositionsObserver(organization, request.getGatewayId());
      }, cancellationExecutor);
   }

   private void addOrganizationExpositionsObserver(String organization, OrganizationExpositionsObserver observer) {
      // First clean up any existing observer for the same gatewayId.
      removeOrganizationExpositionsObserver(organization, observer.gatewayId);

      // Then add the new observer.
      logger.debugf("Adding new observer for organization: '%s', gatewayId: '%s'", organization, observer.gatewayId);
      List<OrganizationExpositionsObserver> registeredObservers = expositionChangeObservers.get(organization);
      registeredObservers.add(observer);
   }

   private void removeOrganizationExpositionsObserver(String organization, String gatewayId) {
      logger.debugf("Removing observer for organization: '%s', gatewayId: '%s'", organization, gatewayId);

      // Use a CopyOnWriteArrayList to avoid ConcurrentModificationException when mixing iteration and modification.
      List<OrganizationExpositionsObserver> registeredObservers = expositionChangeObservers.computeIfAbsent(organization, k -> new CopyOnWriteArrayList<>());
      // Build a list of observers to remove to batch the update of registeredObservers.
      List<OrganizationExpositionsObserver> observersToRemove = new ArrayList<>();
      for (OrganizationExpositionsObserver orgObserver : registeredObservers) {
         if (orgObserver.gatewayId.equals(gatewayId)) {
            logger.infof("<== Removing existing observer for gatewayId: %s", gatewayId);
            observersToRemove.add(orgObserver);
         }
      }
      if (!observersToRemove.isEmpty()) {
         registeredObservers.removeAll(observersToRemove);
      }
   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void fetchExposition(ExpositionFetchRequest request, StreamObserver<io.reshapr.discovery.exposition.v1.Exposition> responseObserver) {
      logger.infof("Received ExpositionFetchRequest for gatewayId: %s", request.getExpositionId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      Exposition exposition = expositionManagerService.getExposition(request.getExpositionId());
      if (exposition != null) {
         io.reshapr.discovery.exposition.v1.Exposition grpcExposition = grpcExpositionFromModel(exposition);
         responseObserver.onNext(grpcExposition);
      } else {
         logger.warnf("No exposition found for ID: %s", request.getExpositionId());
         responseObserver.onError(new IllegalArgumentException("No exposition found for ID: " + request.getExpositionId()));
      }
      responseObserver.onCompleted();
   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void fetchArtifacts(ArtifactsRequest request, StreamObserver<ArtifactsResponse> responseObserver) {
      // When an expositionId is provided, resolve the exposition to filter the attached artifacts by the
      // ConfigurationPlan selection (empty selection = all); the main artifact is always included. Otherwise
      // fall back to the legacy per-service behavior that returns every artifact unfiltered.
      if (request.hasExpositionId() && !request.getExpositionId().isBlank()) {
         fetchArtifactsForExposition(request.getExpositionId(), responseObserver);
         return;
      }

      logger.infof("Received ArtifactsRequest for serviceId: %s", request.getServiceId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      ArtifactsResponse.Builder builder = ArtifactsResponse.newBuilder()
            .setServiceId(request.getServiceId());

      List<Artifact> artifacts = artifactRepository.findByServiceId(request.getServiceId()).list();
      for (Artifact artifact : artifacts) {
         builder.addArtifacts(grpcArtifactFromModel(artifact));
      }

      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
   }

   /**
    * Fetches the artifacts to serve for a given exposition. The main artifact (and the derived protobuf
    * schema) are always included; the attached artifacts are filtered by the exposition's ConfigurationPlan
    * {@code includedArtifacts} selection (an empty or null selection means all attached artifacts apply).
    * @param expositionId The id of the exposition to resolve.
    * @param responseObserver The gRPC response observer.
    */
   private void fetchArtifactsForExposition(String expositionId, StreamObserver<ArtifactsResponse> responseObserver) {
      logger.infof("Received ArtifactsRequest for expositionId: %s", expositionId);
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      Exposition exposition = expositionManagerService.getExposition(expositionId);
      if (exposition == null) {
         logger.warnf("No exposition found for id: %s", expositionId);
         responseObserver.onError(new IllegalArgumentException("No exposition found for ID: " + expositionId));
         return;
      }

      String serviceId = exposition.service.id;
      List<String> includedArtifacts = exposition.configurationPlan.includedArtifacts;
      // A null or empty selection means "all attached artifacts apply".
      Set<String> selectedNames = (includedArtifacts == null) ? Set.of() : new HashSet<>(includedArtifacts);
      boolean selectAll = selectedNames.isEmpty();

      ArtifactsResponse.Builder builder = ArtifactsResponse.newBuilder().setServiceId(serviceId);

      List<Artifact> artifacts = artifactRepository.findByServiceId(serviceId).list();
      for (Artifact artifact : artifacts) {
         if (isAlwaysServed(artifact) || selectAll || selectedNames.contains(artifact.name)) {
            builder.addArtifacts(grpcArtifactFromModel(artifact));
         }
      }

      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
   }

   /**
    * Tells whether an artifact must always be served regardless of the plan selection. This covers the
    * service main artifact and the derived protobuf schema, which define the service capabilities and are
    * never filtered (mirrors {@code ArtifactRepository#findAttachedByServiceId}).
    * @param artifact The artifact to test.
    * @return true if the artifact is always served, false if it is an attached, selectable artifact.
    */
   private boolean isAlwaysServed(Artifact artifact) {
      return artifact.mainArtifact || artifact.type == ArtifactType.PROTOBUF_SCHEMA;
   }

   /**
    * Clears the observer for the given organization and gatewayId.
    *
    * @param organization The organization ID.
    * @param gatewayId    The gateway ID.
    */
   public void clearObserver(String organization, String gatewayId) {
      removeOrganizationExpositionsObserver(organization, gatewayId);
   }

   /**
    * Notifies observers about the creation of a new exposition.
    *
    * @param exposition   The created exposition.
    * @param gatewayGroup The gateway group associated with the exposition.
    */
   public void notifyExpositionCreation(Exposition exposition, GatewayGroup gatewayGroup) {
      logger.debugf("Notifying ExpositionChangeEvent.CREATED for exposition '%s'", exposition.id);

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setExpositionId(exposition.id)
            .setChangeType(ChangeType.CREATED)
            .setExposition(grpcExpositionFromModel(exposition))
            .build();
      notifyObservers(event, gatewayGroup);
   }

   /**
    * Notifies observers about the update of a new exposition.
    *
    * @param exposition   The created exposition.
    * @param gatewayGroup The gateway group associated with the exposition.
    */
   public void notifyExpositionUpdate(Exposition exposition, GatewayGroup gatewayGroup) {
      logger.debugf("Notifying ExpositionChangeEvent.UPDATED for exposition '%s'", exposition.id);

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setExpositionId(exposition.id)
            .setChangeType(ChangeType.UPDATED)
            .setExposition(grpcExpositionFromModel(exposition))
            .build();
      notifyObservers(event, gatewayGroup);
   }

   /**
    * Notifies observers about the deletion of a new exposition.
    *
    * @param exposition   The created exposition.
    * @param gatewayGroup The gateway group associated with the exposition.
    */
   public void notifyExpositionDeletion(Exposition exposition, GatewayGroup gatewayGroup) {
      logger.debugf("Notifying ExpositionChangeEvent.DELETED for exposition '%s'", exposition.id);

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setExpositionId(exposition.id)
            .setChangeType(ChangeType.DELETED)
            .setExposition(grpcExpositionFromModel(exposition))
            .build();
      notifyObservers(event, gatewayGroup);
   }

   private void notifyObservers(ExpositionChangeEvent event, GatewayGroup gatewayGroup) {
      List<OrganizationExpositionsObserver> observers = expositionChangeObservers.get(gatewayGroup.organizationId);
      if (observers != null) {
         observers.stream()
               .filter(observer -> gatewayGroup.labels != null
                     && gatewayGroup.labels.entrySet().containsAll(observer.gatewayLabels.entrySet()))
               .forEach(observer -> {
                  logger.debugf("=> Sending ExpositionChangeEvent to observer for gatewayId: %s", observer.gatewayId);
                  if (observer.observer != null) {
                     try {
                        observer.observer.onNext(event);
                     } catch (Exception e) {
                        logger.errorf("Error sending ExpositionChangeEvent to observer for gatewayId '%s'", observer.gatewayId);
                        observers.remove(observer);
                     }
                  }
               });
      } else {
         logger.warnf("No observers found for gatewayGroup: %s", gatewayGroup.id);
      }
   }

   private record OrganizationExpositionsObserver(String gatewayId, Map<String, String> gatewayLabels,
                                                  StreamObserver<ExpositionChangeEvent> observer) {
   }

   private io.reshapr.discovery.exposition.v1.Exposition grpcExpositionFromModel(Exposition exposition) {
      io.reshapr.discovery.exposition.v1.Exposition.Builder builder = io.reshapr.discovery.exposition.v1.Exposition.newBuilder()
            .setId(exposition.id)
            .setService(grpcServiceFromModel(exposition.service))
            .setConfiguration(grpcConfigurationFromModel(exposition.configurationPlan));
      // Transport the optional organization-unique exposition name (empty string when unnamed).
      if (exposition.name != null) {
         builder.setName(exposition.name);
      }
      return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.Service grpcServiceFromModel(Service service) {
      return io.reshapr.discovery.exposition.v1.Service.newBuilder()
            .setId(service.id)
            .setOrganizationId(service.organizationId)
            .setName(service.name)
            .setVersion(service.version)
            .setType(service.type.name())
            .addAllOperations(grpcOperationsFromModel(service.operations))
            .build();
   }

   private List<io.reshapr.discovery.exposition.v1.Operation> grpcOperationsFromModel(List<Operation> operations) {
      if (operations == null || operations.isEmpty()) {
         return List.of();
      }
      return operations.stream()
            .map(operation -> {
               var opBuilder = io.reshapr.discovery.exposition.v1.Operation.newBuilder().setName(operation.name);
               // We have to deal with optional fields that way...
               if (operation.method != null) {
                  opBuilder.setMethod(operation.method);
               }
               if (operation.action != null) {
                  opBuilder.setAction(operation.action);
               }
               if (operation.inputName != null) {
                  opBuilder.setInputName(operation.inputName);
               }
               if (operation.outputName != null) {
                  opBuilder.setOutputName(operation.outputName);
               }
               return opBuilder.build();
            })
            .toList();
   }

   private io.reshapr.discovery.exposition.v1.Configuration grpcConfigurationFromModel(ConfigurationPlan configuration) {
      io.reshapr.discovery.exposition.v1.Configuration.Builder builder = io.reshapr.discovery.exposition.v1.Configuration.newBuilder()
            .setId(configuration.id)
            .setName(configuration.name)
            .setBackendEndpoint(configuration.backendEndpoint)
            .addAllExcludedOperations(configuration.excludedOperations != null ? configuration.excludedOperations : List.of())
            .addAllIncludedOperations(configuration.includedOperations != null ? configuration.includedOperations : List.of());

      if (configuration.apiKey != null) {
         builder.setApiKey(configuration.apiKey);
      }
      if (configuration.oauth2Configuration != null) {
         io.reshapr.discovery.exposition.v1.OAuth2Configuration.Builder oauth2Builder = io.reshapr.discovery.exposition.v1.OAuth2Configuration.newBuilder()
               .addAllAuthorizationServers(configuration.oauth2Configuration.authorizationServers());
         if (configuration.oauth2Configuration.jwksUri() != null) {
            oauth2Builder.setJwksUri(configuration.oauth2Configuration.jwksUri());
         }
         if (configuration.oauth2Configuration.scopes() != null) {
            oauth2Builder.addAllScopes(configuration.oauth2Configuration.scopes());
         }
         builder.setOauth2Configuration(oauth2Builder.build());
      }
      if (configuration.backendTimeout != null) {
         builder.setBackendTimeout(configuration.backendTimeout);
      }
      if (configuration.backendSecret != null) {
         builder.setBackendSecret(grpcSecretFromModel(configuration.backendSecret));
      }
      builder.setAudit(configuration.audit);
      if (configuration.cachePolicy != null) {
         io.reshapr.discovery.exposition.v1.CachePolicy.Builder cachingBuilder =
               io.reshapr.discovery.exposition.v1.CachePolicy.newBuilder();
         if (configuration.cachePolicy.ttlMs() != null) {
            cachingBuilder.setTtlMs(configuration.cachePolicy.ttlMs());
         }
         if (configuration.cachePolicy.cacheScope() != null) {
            cachingBuilder.setCacheScope(configuration.cachePolicy.cacheScope());
         }
         builder.setCachePolicy(cachingBuilder.build());
      }
      return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.Secret grpcSecretFromModel(Secret secret) {
      io.reshapr.discovery.exposition.v1.Secret.Builder builder = io.reshapr.discovery.exposition.v1.Secret.newBuilder()
            .setName(secret.name);
      if (secret.username != null) {
         builder.setUsername(secret.username);
      }
      if (secret.getPassword() != null) {
         builder.setPassword(secret.getPassword());
      }
      if (secret.getToken() != null) {
         builder.setToken(secret.getToken());
      }
      if (secret.tokenHeader != null) {
         builder.setTokenHeader(secret.tokenHeader);
      }
      if (secret.certPem != null) {
         builder.setCertPem(secret.certPem);
      }
      builder.setUseElicitation(secret.useElicitation);
      if (secret.authMethod != null) {
         builder.setAuthMethod(secret.authMethod.name());
      }
      // Emit the OAuth2 client configuration whenever present: it is used both for the
      // interactive Authorization Code flow (elicitation) and for the machine-to-machine
      // Client Credentials flow (no elicitation).
      if (secret.oauth2ClientConfiguration != null) {
         io.reshapr.discovery.exposition.v1.OAuth2ClientConfiguration.Builder tpOauth2Builder =
               io.reshapr.discovery.exposition.v1.OAuth2ClientConfiguration.newBuilder();
         var oauth2ClientConfiguration = secret.oauth2ClientConfiguration;
         if (oauth2ClientConfiguration.clientId() != null) {
            tpOauth2Builder.setClientId(oauth2ClientConfiguration.clientId());
         }
         if (oauth2ClientConfiguration.clientSecret() != null) {
            tpOauth2Builder.setClientSecret(oauth2ClientConfiguration.clientSecret());
         }
         if (oauth2ClientConfiguration.authorizationEndpoint() != null) {
            tpOauth2Builder.setAuthorizationEndpoint(oauth2ClientConfiguration.authorizationEndpoint());
         }
         if (oauth2ClientConfiguration.tokenEndpoint() != null) {
            tpOauth2Builder.setTokenEndpoint(oauth2ClientConfiguration.tokenEndpoint());
         }
         if (oauth2ClientConfiguration.scopes() != null) {
            tpOauth2Builder.addAllScopes(oauth2ClientConfiguration.scopes());
         }
         builder.setOauth2ClientConfiguration(tpOauth2Builder.build());
      }
      return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.Artifact grpcArtifactFromModel(Artifact artifact) {
      io.reshapr.discovery.exposition.v1.Artifact.Builder builder =
            io.reshapr.discovery.exposition.v1.Artifact.newBuilder()
                  .setId(artifact.id)
                  .setName(artifact.name)
                  .setType(grpcArtifactTypeFromModel(artifact.type))
                  .setContent(artifact.content)
                  .setMainArtifact(artifact.mainArtifact);
      if (artifact.path != null) {
         builder.setPath(artifact.path);
      }
      return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.ArtifactType grpcArtifactTypeFromModel(ArtifactType type) {
      return switch (type) {
         case OPEN_API_SPEC -> io.reshapr.discovery.exposition.v1.ArtifactType.OPEN_API_SPEC;
         case GRAPHQL_SCHEMA -> io.reshapr.discovery.exposition.v1.ArtifactType.GRAPHQL_SCHEMA;
         case PROTOBUF_SCHEMA -> io.reshapr.discovery.exposition.v1.ArtifactType.PROTOBUF_SCHEMA;
         case PROTOBUF_DESCRIPTOR -> io.reshapr.discovery.exposition.v1.ArtifactType.PROTOBUF_DESCRIPTOR;
         case JSON_SCHEMA -> io.reshapr.discovery.exposition.v1.ArtifactType.JSON_SCHEMA;
         case RESHAPR_PROMPTS -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_PROMPTS;
         case RESHAPR_CUSTOM_TOOLS -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_CUSTOM_TOOLS;
         case RESHAPR_RESOURCES -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_RESOURCES;
         case RESHAPR_TOOLS_OUTPUT_FILTERS -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_TOOLS_OUTPUT_FILTERS;
         default ->
               io.reshapr.discovery.exposition.v1.ArtifactType.JSON_FRAGMENT; // Default to JSON_FRAGMENT if unknown
      };
   }
}
