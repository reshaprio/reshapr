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
package io.reshapr.proxy;

import io.reshapr.config.ReshaprArtifactSchemas;
import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.ChangeType;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc;
import io.reshapr.discovery.exposition.v1.MutinyExpositionDiscoveryServiceGrpc;
import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.Mappers;
import io.reshapr.proxy.registry.ResourceEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.registry.ToolEntry;
import io.reshapr.proxy.security.GrpcAuthClientInterceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.grpc.RegisterClientInterceptor;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.util.StringUtil;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

/**
 * Main application class for the reShapr Gateway.
 * It handles the discovery of expositions from the control plane and manages the local registry of services and artifacts.
 * @author laurent
 */
@ApplicationScoped
public class ReshaprGatewayApp {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.getYamlObjectMapper();

   private final Executor propagationExecutor = Executors.newSingleThreadExecutor();

   private final ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceBlockingStub discoveryService;
   private final MutinyExpositionDiscoveryServiceGrpc.MutinyExpositionDiscoveryServiceStub asyncDiscoveryService;
   private final GatewayRegistry gatewayRegistry;
   private final Mappers registryMappers;
   private final WorkCache workCache;

   @ConfigProperty(name = "reshapr.gateway.id")
   String gatewayId;

   @ConfigProperty(name = "reshapr.gateway.labels", defaultValue = "{}")
   Map<String, String> labels;

   @ConfigProperty(name = "reshapr.gateway.fqdns", defaultValue = "localhost:7777")
   List<String> fqdns;

   @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
   String applicationVersion;

   Cancellable expositionChangesSubscription;
   boolean hasConnectedToControlPlane = false;

   /**
    *
    * @param discoveryService
    * @param asyncDiscoveryService
    * @param gatewayRegistry
    * @param registryMappers
    * @param workCache
    */
   public ReshaprGatewayApp(@RegisterClientInterceptor(GrpcAuthClientInterceptor.class) @GrpcClient("exposition-discovery") ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceBlockingStub discoveryService,
                            @RegisterClientInterceptor(GrpcAuthClientInterceptor.class) @GrpcClient("exposition-discovery") MutinyExpositionDiscoveryServiceGrpc.MutinyExpositionDiscoveryServiceStub asyncDiscoveryService,
                            GatewayRegistry gatewayRegistry, Mappers registryMappers, WorkCache workCache) {
      this.discoveryService = discoveryService;
      this.asyncDiscoveryService = asyncDiscoveryService;
      this.gatewayRegistry = gatewayRegistry;
      this.registryMappers = registryMappers;
      this.workCache = workCache;
      logger.info("reShapr Gateway Application is starting...");
   }

   /** Application startup method. */
   void onStart(@Observes StartupEvent ev) {
      validateConfiguration();

      if (logger.isInfoEnabled()) {
         logger.infof("reShapr Gateway Application ID: %s", gatewayId);
         logger.infof("reShapr Gateway Application labels: %s", labels);
         logger.infof("reShapr Gateway Application FQDNs: %s", fqdns);
      }

      try {
         // Initial discovery and fetching of expositions.
         ExpositionDiscoveryResponse discoveryResponse = registerAndDiscoverExpositions();

         // Now we can start reacting to streamed exposition changes.
         ExpositionDiscoveryRequest discoveryRequest = buildDiscoveryRequest();
         expositionChangesSubscription = asyncDiscoveryService.streamExpositionChanges(discoveryRequest)
               .emitOn(propagationExecutor)   // Handle changes on a dedicated thread where blocking client can be used.
               .onFailure().retry().withBackOff(Duration.ofMillis(100), Duration.ofSeconds(5)).indefinitely()
               .onSubscription().invoke(() -> logger.info("Subscribed to exposition changes stream."))
               .onTermination().invoke(() -> logger.warn("Exposition changes stream has been terminated."))
               .subscribe().with(changeEvent -> {
                  logger.debugf("Received exposition change: %s", changeEvent);
                  propagateExpositionChangeEvent(changeEvent);
               }, failure -> logger.errorf("Failed to stream exposition changes: %s", failure.getMessage()));

         hasConnectedToControlPlane = true;
         logger.infof("Startup completed with %d expositions registered. Now listening for changes.", discoveryResponse.getExpositionsCount());
      } catch (Throwable t) {
         logger.error("Failed to fetch expositions during startup", t);
      }
   }

   /**
    * Validate the mandatory gateway configuration at startup. Fails fast with a clear error when
    * {@code reshapr.gateway.fqdns} is missing or empty (e.g., not set via the Helm chart).
    */
   void validateConfiguration() {
      boolean hasFqdn = fqdns != null && fqdns.stream().anyMatch(fqdn -> fqdn != null && !fqdn.isBlank());
      if (!hasFqdn) {
         String message = "Configuration error: reshapr.gateway.fqdns must be set to a non-empty value";
         logger.error(message);
         throw new IllegalStateException(message);
      }
   }

   /** Application shutdown method. */
   void onShutdown(@Observes ShutdownEvent ev) {
      logger.info("reShapr Gateway Application is shutting down...");
      if (expositionChangesSubscription != null) {
         expositionChangesSubscription.cancel();
         logger.info("Cancelled exposition changes subscription.");
      }
   }

   /** Check if the gateway has successfully connected to the control plane at least once. */
   public boolean hasConnectedToControlPlane() {
      return hasConnectedToControlPlane;
   }

   /** Build the exposition discovery request. */
   public ExpositionDiscoveryRequest buildDiscoveryRequest() {
      return ExpositionDiscoveryRequest.newBuilder()
            .setGatewayId(gatewayId)
            .putAllLabels(labels)
            .addAllFqdns(fqdns)
            .setVersion(applicationVersion)
            .build();
   }

   /** Register again within the control plane and re-discover expositions. */
   public ExpositionDiscoveryResponse registerAndDiscoverExpositions() {
      ExpositionDiscoveryRequest discoveryRequest = buildDiscoveryRequest();

      ExpositionDiscoveryResponse discoveryResponse = discoveryService.discoverExpositions(discoveryRequest);
      logger.infof("Fetched %d expositions during registration", discoveryResponse.getExpositionsCount());
      hasConnectedToControlPlane = true;

      discoveryResponse.getExpositionsList().forEach(this::fetchExposition);
      return discoveryResponse;
   }

   /** Handle the exposition change here, e.g., update the registry or notify other components. */
   void propagateExpositionChangeEvent(ExpositionChangeEvent changeEvent) {

      Exposition exposition = changeEvent.getExposition();
      if (changeEvent.getChangeType() == ChangeType.CREATED) {
         // Fetch service details and register it.
         logger.infof("Start exposing Service '%s:%s' with ID '%s' (exposition '%s')",
               exposition.getService().getName(),
               exposition.getService().getVersion(),
               exposition.getService().getId(),
               exposition.getId());
         fetchExposition(exposition);
      } else if (changeEvent.getChangeType() == ChangeType.DELETED) {
         // Remove only this exposition from the registry (other expositions of the same service are kept).
         // No work cache invalidation is needed: the artifacts' content did not change, so their parsed
         // entries (keyed by artifact id) remain valid for any other exposition still referencing them, and
         // orphaned entries are eventually evicted by the LRU cache.
         logger.infof("Stop exposing Service '%s:%s' with ID '%s' (exposition '%s')",
               exposition.getService().getName(),
               exposition.getService().getVersion(),
               exposition.getService().getId(),
               exposition.getId());
         gatewayRegistry.removeExposition(exposition.getId());
      } else if (changeEvent.getChangeType() == ChangeType.UPDATED) {
         // Re-fetch and re-register the exposition. fetchExposition compares the freshly fetched artifacts
         // with the previously registered ones and surgically invalidates the work cache only for the
         // artifacts whose content actually changed, then addExposition atomically replaces the instance
         // (refreshing the name index and re-electing).
         logger.infof("Update exposition for Service '%s:%s' with ID '%s' (exposition '%s')",
               exposition.getService().getName(),
               exposition.getService().getVersion(),
               exposition.getService().getId(),
               exposition.getId());
         fetchExposition(exposition);
      }
   }

   /** Fetch exposition details and register the service and its artifacts. */
   void fetchExposition(Exposition exposition) {
      logger.infof("Fetching artifacts for exposition '%s' of Service '%s:%s'", exposition.getId(),
            exposition.getService().getName(), exposition.getService().getVersion());

      ServiceEntry service = registryMappers.toServiceEntry(exposition.getService());
      ConfigurationEntry configuration = registryMappers.toConfigurationEntry(exposition.getConfiguration());
      logger.debugf("ConfigurationPlan for exposition '%s' is %s", exposition.getId(), configuration);

      // Request the artifacts scoped to this exposition: the control plane filters the attached artifacts by
      // the ConfigurationPlan selection (empty selection = all) and always includes the main artifact.
      ArtifactsRequest artifactsRequest = ArtifactsRequest.newBuilder()
            .setExpositionId(exposition.getId())
            .setServiceId(exposition.getService().getId())
            .build();
      ArtifactsResponse artifactsResponse = discoveryService.fetchArtifacts(artifactsRequest);
      logger.debugf("Fetched %d artifacts for exposition '%s'", artifactsResponse.getArtifactsCount(), exposition.getId());

      ArtifactEntry mainArtifact = null;
      List<ArtifactEntry> attachedArtifacts = new ArrayList<>();
      for (var artifact : artifactsResponse.getArtifactsList()) {
         ArtifactEntry artifactEntry = registryMappers.toArtifactEntry(artifact);

         // We don't need the protobuf schema for the service, so we skip it.
         if (!artifactEntry.type().equals(ArtifactEntryType.PROTOBUF_SCHEMA)) {
            if (artifactEntry.mainArtifact() && isRootArtifact(artifactEntry)) {
               logger.debugf("Registering Artifact with ID '%s' for exposition %s as main", artifactEntry.id(), exposition.getId());
               mainArtifact = artifactEntry;
            } else {
               logger.debugf("Registering Artifact with ID '%s' for exposition %s as attached", artifactEntry.id(), exposition.getId());
               attachedArtifacts.add(artifactEntry);
            }
         }
      }

      // Build the ready-to-serve aggregate (main/attached already split, attached already filtered by the
      // control plane) and register it atomically under all exposition indexes.
      ExpositionEntry expositionEntry = new ExpositionEntry(exposition.getId(),
            exposition.getName().isBlank() ? null : exposition.getName(),
            service, configuration, mainArtifact, attachedArtifacts);

      // Surgically invalidate the work cache for artifacts whose content actually changed since the
      // previously registered version of this exposition (Option 2b). Unchanged or brand-new artifacts keep
      // their (still valid or not-yet-computed) cache entries, avoiding cross-exposition over-invalidation.
      ExpositionEntry previous = gatewayRegistry.getExpositionById(exposition.getId());
      invalidateChangedArtifacts(previous, mainArtifact, attachedArtifacts);

      logger.infof("Registering exposition '%s' for Service with ID '%s' in organization %s",
            exposition.getId(), service.id(), service.organizationId());
      gatewayRegistry.addExposition(expositionEntry);

      // Check if we need to process any attached artifact for special handling (UI resources).
      if (!attachedArtifacts.isEmpty()) {
         analyseAttachedArtifacts(attachedArtifacts, service);
      }
   }

   /**
    * Surgically invalidate the work cache for artifacts whose content changed between the previously
    * registered exposition and the freshly fetched one. The work cache is keyed by artifact id, so parsed
    * artifacts are shared across expositions referencing the same artifact; invalidating only the artifacts
    * whose content actually changed avoids evicting entries still valid for other expositions and prevents
    * repeated invalidate/rebuild churn when several exposition change events arrive in series.
    * @param previous The previously registered exposition (may be null for CREATED / startup).
    * @param newMain The freshly fetched main artifact (may be null).
    * @param newAttached The freshly fetched attached artifacts.
    */
   void invalidateChangedArtifacts(@Nullable ExpositionEntry previous, @Nullable ArtifactEntry newMain,
                                           List<ArtifactEntry> newAttached) {
      if (previous == null) {
         // Nothing was registered before for this exposition: new artifacts are not cached yet (or are
         // shared with the identical content of another exposition), so there is nothing to invalidate.
         return;
      }

      // Index the previous artifacts content by id.
      Map<String, String> previousContentById = new HashMap<>();
      if (previous.mainArtifact() != null) {
         previousContentById.put(previous.mainArtifact().id(), previous.mainArtifact().content());
      }
      for (ArtifactEntry previousArtifact : previous.attachedArtifacts()) {
         previousContentById.put(previousArtifact.id(), previousArtifact.content());
      }

      // Collect the freshly fetched artifacts.
      List<ArtifactEntry> newArtifacts = new ArrayList<>();
      if (newMain != null) {
         newArtifacts.add(newMain);
      }
      newArtifacts.addAll(newAttached);

      // Invalidate only the artifacts whose content actually changed.
      for (ArtifactEntry artifact : newArtifacts) {
         String previousContent = previousContentById.get(artifact.id());
         if (previousContent != null && !previousContent.equals(artifact.content())) {
            logger.debugf("Artifact '%s' content changed, invalidating its work cache entries", artifact.id());
            workCache.invalidateMajor(artifact.id());
         }
      }
   }

   private boolean isRootArtifact(ArtifactEntry artifact) {
      return StringUtil.isNullOrEmpty(artifact.path());
   }

   private void analyseAttachedArtifacts(List<ArtifactEntry> attachedArtifacts, ServiceEntry service) {
      try {
         for (ArtifactEntry attachedArtifact : attachedArtifacts) {
            JsonNode artifactNode = YAML_MAPPER.readTree(attachedArtifact.content());
            String kind = artifactNode.path("kind").asText();
            String version = artifactNode.path("apiVersion").asText();
            if (ReshaprArtifactSchemas.RESOURCES_KIND.equals(kind)
                  && ReshaprArtifactSchemas.RESOURCES_VERSION_V1ALPHA1.equals(version)) {
               // Further processing of Resources artifact to check uiResources.
               JsonNode resources = artifactNode.path("resources");
               analyseResources(service, resources);
            }
         }
      } catch (Exception e) {
         logger.errorf("Failed to parse attached artifact content: %s", e.getMessage());
      }
   }

   private void analyseResources(ServiceEntry service, JsonNode resources) {
      for (var resource : resources.properties()) {
         // Check for UI Tool resources only.
         if (resource.getKey().startsWith("ui://")) {
            JsonNode toolsNode = resource.getValue().path("tools");
            if (toolsNode != null && toolsNode.isArray()) {
               for (JsonNode toolNode : toolsNode) {
                  analyseResourceTool(service, resource, toolNode);
               }
            }
         }
      }
   }

   private void analyseResourceTool(ServiceEntry service, Map.Entry<String, JsonNode> resource, JsonNode toolNode) {
      ToolEntry toolEntry = null;
      ResourceEntry resourceEntry = null;

      if (toolNode.isTextual()) {
         toolEntry = new ToolEntry(service.id(), service.organizationId(), toolNode.asText());
         resourceEntry = new ResourceEntry("ui", resource.getKey(), new String[]{"app", "model"});
      } else {
         Map.Entry<String, JsonNode> toolNodeEntry = toolNode.properties().stream().findFirst().get();
         toolEntry = new ToolEntry(service.id(), service.organizationId(), toolNodeEntry.getKey());
         resourceEntry = new ResourceEntry("ui", resource.getKey(),
               toolNodeEntry.getValue().path("visibility").isArray() ?
                     StreamSupport.stream(toolNodeEntry.getValue().path("visibility").spliterator(), false)
                           .map(JsonNode::asText).toArray(String[]::new)
                     : new String[]{"app", "model"}
         );
      }
      logger.infof("Registering UI Resource '%s' for Tool '%s' for Service with ID '%s'",
            resourceEntry, toolEntry, service.id());
      gatewayRegistry.addResourceForTool(toolEntry, resourceEntry);
   }
}
