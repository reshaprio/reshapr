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
import io.quarkus.security.Authenticated;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.repository.SecretRepository;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * SecretResource provides REST endpoints for managing secrets.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/api/v1/secrets")
public class SecretResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final SecretRepository secretRepository;
   private final Mappers v1Mappers;
   public SecretResource(SecretRepository secretRepository, Mappers v1Mappers) {
      this.secretRepository = secretRepository;
      this.v1Mappers = v1Mappers;
   }

   @GET
   @Authenticated
   public List<SecretDTO> getSecrets(@QueryParam("page") @DefaultValue("0") int page,
         @QueryParam("size") @DefaultValue("20") int size) {
      return secretRepository.findAll(Sort.ascending("name")).page(Page.of(page, size))
            .list().stream()
            .map(v1Mappers::toResource)
            .toList();
   }

   @GET
   @Authenticated
   @Path("/refs")
   public List<SecretReferenceDTO> getSecretReferences(@QueryParam("page") @DefaultValue("0") int page,
         @QueryParam("size") @DefaultValue("20") int size) {
      return secretRepository.findAll(Sort.ascending("name")).page(Page.of(page, size))
            .project(SecretReferenceDTO.class).list();
   }

   @POST
   @Authenticated
   @Transactional
   @Produces(MediaType.APPLICATION_JSON)
   public Response createSecret(@Valid SecretDTO secretDTO) {
      logger.debugf("Creating new secret with name %s", secretDTO.name());

      // Check if secret already exist first.
      Secret secret = secretRepository.findByName(secretDTO.name());
      if (secret != null) {
         logger.warnf("Secret with name %s already exists", secretDTO.name());
         return Response.status(Response.Status.CONFLICT.getStatusCode(), "Secret with same name already exists").build();
      }

      secret = v1Mappers.fromResource(secretDTO);
      secretRepository.persistAndFlush(secret);
      return Response.status(Response.Status.CREATED).entity(v1Mappers.toResource(secret)).build();
   }

   @GET
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getSecret(@PathParam("id") String id) {
      logger.debugf("Retrieving secret with id %s", id);
      Secret secret = secretRepository.findById(id);
      if (secret == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return Response.ok(v1Mappers.toResource(secret)).build();
   }

   @PUT
   @Authenticated
   @Transactional
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response updateSecret(@PathParam("id") String id, @Valid SecretDTO secretDTO) {
      logger.debugf("Updating secret with id %s", id);
      Secret secret = secretRepository.findById(id);
      if (secret == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      // Map new values from DTO - we cannot use MapStruct here because we'd have a detached entity.
      // We need to preserve id and organizationId.
      secret.name = secretDTO.name();
      secret.type = secretDTO.type();
      secret.description = secretDTO.description();
      secret.username = secretDTO.username();
      secret.tokenHeader = secretDTO.tokenHeader();
      secret.certPem = secretDTO.certPem();
      // Only update password and token if they are not masked (starting with *****).
      if (secretDTO.password() != null && !secretDTO.password().startsWith("*****")) {
         secret.setPassword(secretDTO.password());
      }
      if (secretDTO.token() != null && !secretDTO.token().startsWith("*****")) {
         secret.setToken(secretDTO.token());
      }
      if (secretDTO.useElicitation() && secretDTO.oauth2ClientConfiguration() != null) {
         OAuth2ClientConfigurationDTO oauth2ClientConfig = secretDTO.oauth2ClientConfiguration();
         secret.oauth2ClientConfiguration = new Secret.OAuth2ClientConfiguration(oauth2ClientConfig.clientId(),
               oauth2ClientConfig.clientSecret(),
               oauth2ClientConfig.authorizationEndpoint(),
               oauth2ClientConfig.tokenEndpoint());
         secret.useElicitation = true;
      }

      secretRepository.persist(secret);
      return Response.ok(v1Mappers.toResource(secret)).build();
   }

   @DELETE
   @Authenticated
   @Transactional
   @Path("/{id}")
   public Response deleteSecret(@PathParam("id") String id) {
      logger.debugf("Deleting secret with id %s", id);
      Secret secret = secretRepository.findById(id);
      if (secret == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      secretRepository.delete(secret);
      return Response.noContent().build();
   }
}
