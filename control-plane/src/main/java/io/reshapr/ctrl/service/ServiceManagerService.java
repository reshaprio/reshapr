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

import io.reshapr.ctrl.artifacts.ArtifactImportException;
import io.reshapr.ctrl.artifacts.ArtifactImporter;
import io.reshapr.ctrl.artifacts.ArtifactImporterFactory;
import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.OperationNameFilterPredicate;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.repository.SecretRepository;
import io.reshapr.ctrl.repository.ServiceRepository;
import io.reshapr.ctrl.artifacts.ReshaprArtifactBuilder;
import io.reshapr.ctrl.artifacts.ReshaprArtifactException;
import io.reshapr.util.HttpDownloader;
import io.reshapr.util.HttpSecret;
import io.reshapr.util.ReferenceResolver;
import io.reshapr.util.RelativeReferenceURLBuilderFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Predicate;

/**
 * Service for managing service definitions in the Reshapr control plane.
 * @author laurent
 */
@ApplicationScoped
public class ServiceManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final SecretRepository secretRepository;
   private final ServiceRepository serviceRepository;
   private final ArtifactRepository artifactRepository;
   private final ConfigurationPlanManagerService configurationPlanManagerService;
   private final ExpositionManagerService expositionManagerService;

   public ServiceManagerService(SecretRepository secretRepository, ServiceRepository serviceRepository,
                                ArtifactRepository artifactRepository, ConfigurationPlanManagerService configurationPlanManagerService,
                                ExpositionManagerService expositionManagerService) {
      this.secretRepository = secretRepository;
      this.serviceRepository = serviceRepository;
      this.artifactRepository = artifactRepository;
      this.configurationPlanManagerService = configurationPlanManagerService;
      this.expositionManagerService = expositionManagerService;
   }

   /**
    * Imports a service definition from a remote URL.
    * @param url The URL of the remote service specification.
    * @param secretName The name of the secret to use for authentication (optional).
    * @param mainArtifact Whether this is the main artifact for the service.
    * @param serviceInfo Optional overriding information on the service to discover (maybe null).
    * @return The imported service.
    * @throws ArtifactImportException If an error occurs during import.
    */
   public Service importRemoteSpecification(String url, String secretName, boolean mainArtifact, ServiceInfo serviceInfo)
         throws ArtifactImportException {
      logger.debugf("Importing Service definition from remote URL %s", url);

      Secret secret = null;
      if (secretName != null) {
         secret = secretRepository.findByName(secretName);
         logger.debugf("Secret %s was requested. Have we found it? %s", secretName, (secret != null));
      }

      File specificationFile = null;
      try {
         HttpSecret httpSecret = null;
         if (secret != null) {
            httpSecret = new HttpSecret(secret.username, secret.getPassword(), secret.getToken(), secret.tokenHeader, secret.certPem);
         }

         // Download remote to local file before import.
         HttpDownloader.FileAndHeaders fileAndHeaders = HttpDownloader.handleHTTPDownloadToFileAndHeaders(url,
               httpSecret, true);
         specificationFile = fileAndHeaders.localFile();

         // Import the specification file.
         return importSpecificationFile(
               new SpecificationArtifactInfo(url, specificationFile, mainArtifact),
               new ReferenceResolver(url, httpSecret, true,
                     RelativeReferenceURLBuilderFactory
                           .getRelativeReferenceURLBuilder(fileAndHeaders.responseHeaders())),
               serviceInfo
         );

      } catch (IOException ioe) {
         logger.errorf("Unable to download specification file from URL %s: %s", url, ioe.getMessage());
         throw new ArtifactImportException(ioe.getMessage(), ioe);
      } finally {
         // Cleanup and remove local file.
         if (specificationFile != null) {
            try {
               Files.deleteIfExists(specificationFile.toPath());
            } catch (IOException e) {
               logger.warnf("Failed to delete temporary specification file %s: %s", specificationFile, e.getMessage());
            }
         }
      }
   }

   /**
    * Imports a service definition from the given repository file.
    * @param artifactInfo Information about the specification artifact being imported.
    * @param referenceResolver A reference resolver to use for relative references (maybe null).
    * @param serviceInfo Optional overriding information on the service to discover (maybe null).
    * @return The imported service.
    * @throws ArtifactImportException If an error occurs during import.
    */
   @Transactional
   public Service importSpecificationFile(SpecificationArtifactInfo artifactInfo, ReferenceResolver referenceResolver,
                                          ServiceInfo serviceInfo) throws ArtifactImportException {
      logger.debugf("Importing Service definition from artifact %s", artifactInfo.specificationFile().getName());

      // Retrieve the correct importer based on file path.
      ArtifactImporter importer = null;
      try {
         importer = ArtifactImporterFactory.getArtifactImporter(artifactInfo.specificationFile(), referenceResolver);
      } catch (IOException ioe) {
         logger.errorf("Unable to create importer for file %s: %s", artifactInfo.specificationFile().getName(), ioe.getMessage());
         throw new ArtifactImportException(ioe.getMessage(), ioe);
      }

      // Prepare operation filtering predicate.
      Predicate<Operation> filterPredicate = new OperationNameFilterPredicate(null, null);

      // Check serviceInfo for name, version and operation overrides.
      if (serviceInfo != null) {
         importer.setServiceName(serviceInfo.name());
         importer.setServiceVersion(serviceInfo.version());
         filterPredicate = new OperationNameFilterPredicate(serviceInfo.includedOperations(), serviceInfo.excludedOperations());
      }

      List<Service> services = importer.getServiceDefinitions();
      if (services.size() != 1) {
         logger.warnf("Expected exactly one service definition in the artifact, found %d", services.size());
         throw new ArtifactImportException("Expected exactly one service definition in the artifact " + artifactInfo.name());
      }

      Service discoveredService = services.getFirst();
      Service service = serviceRepository.findByNameAndVersion(discoveredService.name, discoveredService.version);

      if (service == null) {
         logger.debugf("Creating a new Service %s", discoveredService.name);
         service = discoveredService;
         service.createdOn = OffsetDateTime.now();
      }

      // Set or update operation that may have changed since previous import.
      service.operations = discoveredService.operations.stream()
            .filter(filterPredicate)
            .toList();
      serviceRepository.persist(service);

      // Remove previous artifacts attached to service if any.
      if (artifactInfo.mainArtifact()) {
         artifactRepository.delete("where service.id = ?1 and mainArtifact=true", service.id);
      } else {
         artifactRepository.delete("where service.id = ?1 and sourceArtifact = ?2", service.id, artifactInfo.name());
      }

      // Discover and persist new artifacts from the Service definition.
      final Service finalService = service;
      List<Artifact> artifacts = importer.getArtifactDefinitions(discoveredService).stream()
            .peek(artifact -> {
               artifact.service = finalService;
               artifact.sourceArtifact = artifactInfo.name();
               artifact.mainArtifact = artifactInfo.mainArtifact();
            })
            .toList();
      artifactRepository.persist(artifacts);

      // Propagate changes on artifacts if necessary before returning.
      propagateArtifactsChanges(service);
      return service;
   }

   @Transactional
   public boolean deleteService(String serviceId) {
      Service service = serviceRepository.findById(serviceId);
      if (service != null) {
         logger.infof("Deleting Service definition with id %s", serviceId);
         artifactRepository.delete("service.id = ?1", service.id);
         List<ConfigurationPlan> configurationPlans = configurationPlanManagerService.getConfigurationPlans(serviceId);
         configurationPlans.forEach(configurationPlanManagerService::deleteConfigurationPlan);
         serviceRepository.delete(service);
         return true;
      }
      return false;
   }

   /**
    * Attach an artifact to a service from a remote URL.
    * @param url The URL of the remote artifact.
    * @param secretName The name of the secret to use for authentication (optional).
    * @return The attached artifact.
    * @throws ReshaprArtifactException If an error occurs during attachment.
    */
   public Artifact attachRemoteArtifact(String url, String secretName) throws ReshaprArtifactException {
      logger.debugf("Attaching artifact to Service from remote URL %s", url);

      Secret secret = null;
      if (secretName != null) {
         secret = secretRepository.findByName(secretName);
         logger.debugf("Secret %s was requested. Have we found it? %s", secretName, (secret != null));
      }

      File artifactFile = null;
      try {
         HttpSecret httpSecret = null;
         if (secret != null) {
            httpSecret = new HttpSecret(secret.username, secret.getPassword(), secret.getToken(), secret.tokenHeader, secret.certPem);
         }

         // Download remote to local file before import.
         HttpDownloader.FileAndHeaders fileAndHeaders = HttpDownloader.handleHTTPDownloadToFileAndHeaders(url,
               httpSecret, true);
         artifactFile = fileAndHeaders.localFile();

         // Attach the artifact file.
         return attachArtifactFile(new AttachmentArtifactInfo(url, artifactFile));
      } catch (IOException ioe) {
         logger.errorf("Unable to download artifact file from URL %s: %s", url, ioe.getMessage());
         throw new ReshaprArtifactException(ioe.getMessage(), ioe);
      } finally {
         // Cleanup and remove local file.
         if (artifactFile != null) {
            try {
               Files.deleteIfExists(artifactFile.toPath());
            } catch (IOException e) {
               logger.warnf("Failed to delete temporary artifact file %s: %s", artifactFile, e.getMessage());
            }
         }
      }
   }

   /**
    * Attach an artifact to a service from a local file.
    * @param artifactInfo Information about the artifact being attached.
    * @return The attached artifact.
    * @throws ReshaprArtifactException If an error occurs during attachment.
    */
   @Transactional
   public Artifact attachArtifactFile(AttachmentArtifactInfo artifactInfo) throws ReshaprArtifactException {
      logger.debugf("Attaching information from artifact %s", artifactInfo.name());

      // Retrieve the correct importer based on file path.
      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact(artifactInfo.name(), artifactInfo.attachmentFile());

      // Find the service to attach artifact to.
      Service service = serviceRepository.findByNameAndVersion(
            artifactWithServiceRef.serviceName(), artifactWithServiceRef.serviceVersion());
      if (service == null) {
         logger.errorf("No Service found with name %s and version %s to attach artifact %s",
               artifactWithServiceRef.serviceName(), artifactWithServiceRef.serviceVersion(), artifactWithServiceRef.artifact().name);
         throw new ReshaprArtifactException("No Service found with name " + artifactWithServiceRef.serviceName() +
               " and version " + artifactWithServiceRef.serviceVersion());
      }

      // Access to artifact information.
      Artifact parsedArtifact = artifactWithServiceRef.artifact();

      // Upsert semantics, mirroring importSpecificationFile(): artifact names are unique within a service
      // (ux_artifacts_service_name), so a re-attachment (e.g. after editing the content) must update the
      // existing artifact in place rather than inserting a duplicate — which would violate the constraint.
      Artifact artifact = artifactRepository.findByServiceIdAndName(service.id, parsedArtifact.name);
      if (artifact != null) {
         logger.debugf("Updating existing attached artifact '%s' for service '%s'", parsedArtifact.name, service.id);
         artifact.content = parsedArtifact.content;
         artifact.type = parsedArtifact.type;
         artifact.capabilities = parsedArtifact.capabilities;
         artifact.path = parsedArtifact.path;
         artifact.mainArtifact = false;
         artifact.sourceArtifact = artifactInfo.name();
         // Managed entity: changes are flushed on transaction commit, no explicit persist needed.
      } else {
         artifact = parsedArtifact;
         artifact.service = service;
         artifact.mainArtifact = false;
         artifact.sourceArtifact = artifactInfo.name();
         artifactRepository.persist(artifact);
      }

      // Propagate changes to exposition before returning.
      propagateArtifactsChanges(service);
      return artifact;
   }

   /** Triggers the potential updates of expositions when a Service artifact has changed. */
   protected void propagateArtifactsChanges(Service service) {
      logger.infof("Propagating artifact changes for service '%s'", service.id);
      expositionManagerService.propagateServiceChanges(service);
   }
}
