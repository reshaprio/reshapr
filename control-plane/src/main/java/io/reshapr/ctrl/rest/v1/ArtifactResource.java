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

import io.reshapr.ctrl.artifacts.ArtifactImportException;
import io.reshapr.ctrl.artifacts.ReshaprArtifactException;
import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.service.ArtifactManagerService;
import io.reshapr.ctrl.service.AttachmentArtifactInfo;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.ServiceInfo;
import io.reshapr.ctrl.service.SpecificationArtifactInfo;
import io.reshapr.ctrl.service.ServiceManagerService;

import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;

/**
 * ArtifactResource provides REST endpoints for managing artifacts.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/api/v1/artifacts")
public class ArtifactResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ServiceManagerService serviceManagerService;
   private final ArtifactManagerService artifactManagerService;
   private final ArtifactRepository artifactRepository;
   private final Mappers v1Mappers;

   public ArtifactResource(ServiceManagerService serviceManagerService, ArtifactManagerService artifactManagerService,
         ArtifactRepository artifactRepository, Mappers v1Mappers) {
      this.serviceManagerService = serviceManagerService;
      this.artifactManagerService = artifactManagerService;
      this.artifactRepository = artifactRepository;
      this.v1Mappers = v1Mappers;
   }

   @GET
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getArtifact(@PathParam("id") String id) {
      logger.debugf("Retrieving artifact with id%s", id);
      Artifact artifact = artifactRepository.findById(id);
      if (artifact == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return Response.ok(v1Mappers.toResource(artifact)).build();
   }

   @GET
   @Authenticated
   @Path("/{id}/deletion-impact")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getArtifactDeletionImpact(@PathParam("id") String id) {
      logger.debugf("Computing deletion impact for artifact with id %s", id);
      try {
         return Response.ok(v1Mappers.toResource(artifactManagerService.getArtifactDeletionImpact(id))).build();
      } catch (DependencyNotFoundException e) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
   }

   @DELETE
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response deleteArtifact(@PathParam("id") String id) {
      logger.debugf("Deleting artifact with id %s", id);
      try {
         return Response.ok(v1Mappers.toResource(artifactManagerService.deleteArtifact(id))).build();
      } catch (DependencyNotFoundException e) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
   }

   @GET
   @Authenticated
   @Path("/service/{serviceId}")
   public List<ArtifactDTO> getArtifactsByServiceId(@PathParam("serviceId") String serviceId) {
      logger.debugf("Getting artifacts for service with id %s", serviceId);
      return artifactRepository.findByServiceId(serviceId).project(ArtifactDTO.class).list();
   }

   @GET
   @Authenticated
   @Path("/service/{serviceId}/refs")
   public List<ArtifactReferenceDTO> getArtifactReferencesByServiceId(@PathParam("serviceId") String serviceId) {
      logger.debugf("Getting artifacts refs for service with id %s", serviceId);
      return artifactRepository.findByServiceId(serviceId).project(ArtifactReferenceDTO.class).list();
   }

   @POST
   @Authenticated
   @Consumes(MediaType.MULTIPART_FORM_DATA)
   @Produces(MediaType.APPLICATION_JSON)
   public Response importArtifact(@RestForm("file") FileUpload file,
                                  @RestForm("mainArtifact") @DefaultValue("true") boolean mainArtifact,
                                  @RestForm("serviceName") String serviceName,
                                  @RestForm("serviceVersion") String serviceVersion,
                                  @RestForm("includedOperations") List<String> includedOperations,
                                  @RestForm("excludedOperations") List<String> excludedOperations) {

      if (file.uploadedFile() != null && file.uploadedFile().toFile().exists()) {
         logger.debugf("Importing local artifact %s with type %s", file.name(), file.contentType());
         logger.debugf("Main artifact: %s", mainArtifact);
         try {
            Service service = serviceManagerService.importSpecificationFile(
                  new SpecificationArtifactInfo(file.fileName(), file.uploadedFile().toFile(), mainArtifact),
                  null, new ServiceInfo(serviceName, serviceVersion, includedOperations, excludedOperations));
            return Response.ok(v1Mappers.toResource(service)).build();
         } catch (ArtifactImportException e) {
            logger.warnf("Validation error importing artifact from uploaded file: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO(e.getMessage())).build();
         } catch (Exception e) {
            logger.error("Error importing artifact from uploaded file", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorDTO("Error importing artifact")).build();
         }
      }
      logger.warn("No uploaded file provided for artifact import");
      return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO("No uploaded file provided")).build();
   }

   @POST
   @Authenticated
   @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
   @Produces(MediaType.APPLICATION_JSON)
   public Response importRemoteArtifact(@FormParam("url") String url,
                                        @FormParam("mainArtifact") @DefaultValue("true") boolean mainArtifact,
                                        @FormParam("secretName") String secretName,
                                        @FormParam("serviceName") String serviceName,
                                        @FormParam("serviceVersion") String serviceVersion,
                                        @FormParam("includedOperations") List<String> includedOperations,
                                        @FormParam("excludedOperations") List<String> excludedOperations) {

      if (url != null && !url.isEmpty()) {
         logger.debugf("Importing artifact from remote URL %s", url);
         logger.debugf("Main artifact: %s", mainArtifact);
         try {
            Service service = serviceManagerService.importRemoteSpecification(url, secretName, mainArtifact,
                  new ServiceInfo(serviceName, serviceVersion, includedOperations, excludedOperations));
            return Response.ok(v1Mappers.toResource(service)).build();
         } catch (ArtifactImportException e) {
            logger.warnf("Validation error importing artifact from remote URL: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO(e.getMessage())).build();
         } catch (Exception e) {
            logger.error("Error importing artifact from remote URL", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorDTO("Error importing artifact")).build();
         }
      }
      logger.warn("No URL provided for artifact import");
      return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO("No URL provided")).build();
   }

   @POST
   @Authenticated
   @Consumes(MediaType.MULTIPART_FORM_DATA)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("/attach")
   public Response attachArtifact(@RestForm("file") FileUpload file) {
      if (file.uploadedFile() != null && file.uploadedFile().toFile().exists()) {
         logger.debugf("Attaching local artifact %s with type %s", file.name(), file.contentType());
         try {
            Artifact artifact = serviceManagerService.attachArtifactFile(
                  new AttachmentArtifactInfo(file.fileName(), file.uploadedFile().toFile()));
            return Response.ok(v1Mappers.toResource(artifact)).build();
         } catch (ReshaprArtifactException e) {
            logger.warnf("Validation error attaching artifact from uploaded file: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO(e.getMessage())).build();
         } catch (Exception e) {
            logger.error("Error attaching artifact from uploaded file", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorDTO("Error attaching artifact")).build();
         }
      }
      logger.warn("No uploaded file provided for artifact attach");
      return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO("No uploaded file provided")).build();
   }

   @POST
   @Authenticated
   @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
   @Produces(MediaType.APPLICATION_JSON)
   @Path("/attach")
   public Response attachRemoteArtifact(@FormParam("url") String url, @FormParam("secretName") String secretName) {
      if (url != null && !url.isEmpty()) {
         logger.debugf("Attaching artifact from remote URL %s", url);
         try {
            Artifact artifact = serviceManagerService.attachRemoteArtifact(url, secretName);
            return Response.ok(v1Mappers.toResource(artifact)).build();
         } catch (ReshaprArtifactException e) {
            logger.warnf("Validation error attaching artifact from remote URL: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO(e.getMessage())).build();
         } catch (Exception e) {
            logger.error("Error attaching artifact from remote URL", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorDTO("Error attaching artifact")).build();
         }
      }
      logger.warn("No URL provided for artifact attach");
      return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorDTO("No URL provided")).build();
   }
}
