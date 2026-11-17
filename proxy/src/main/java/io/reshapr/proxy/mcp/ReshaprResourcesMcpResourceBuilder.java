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
package io.reshapr.proxy.mcp;

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ContentUtil;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.net.HttpHeaders;
import com.google.crypto.tink.subtle.Base64;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * An implementation of McpResourceBuilder that builds resources from Reshapr Resources artifacts.
 * @author laurent
 */
public class ReshaprResourcesMcpResourceBuilder implements McpResourceBuilder {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "rrmcprb-";

   private static final String TEXT_PLAIN_MIME_TYPE = "text/plain";
   private static final String APPLICATION_OCTET_STREAM_MIME_TYPE = "application/octet-stream";
   private static final String RESOURCE_MIME_TYPE = "mimeType";

   private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.getYamlObjectMapper();

   private final ServiceEntry service;
   private final List<ArtifactEntry> attachedArtifacts;
   private final WorkCache workCache;
   private final ProxyService proxyService;

   public ReshaprResourcesMcpResourceBuilder(ServiceEntry service, List<ArtifactEntry> attachedArtifacts,
                                             WorkCache workCache, ObjectMapper mapper, ProxyService proxyService) {
      this.service = service;
      this.attachedArtifacts = attachedArtifacts;
      this.workCache = workCache;
      this.proxyService = proxyService;
   }

   @Override
   public List<McpSchema.Resource> listResources() {
      // First, check cache to see if we have already loaded resources.
      JsonNode resourcesNode = getResourcesNode();
      if (resourcesNode == null) {
         logger.debugf("No Resources artifact found for service '%s'", service.id());
         return Collections.emptyList();
      }

      // Convert to list of Resource objects.
      List<McpSchema.Resource> resources = new ArrayList<>();
      Iterator<String> urisIterator = resourcesNode.fieldNames();
      while (urisIterator.hasNext()) {
         String uri = urisIterator.next();

         JsonNode resourceNode = resourcesNode.get(uri);
         Long size = resourceNode.has("size") ? resourceNode.get("size").asLong() : null;
         var annotations = resourceNode.has(McpSchema.ATTR_ANNOTATIONS) ?
               YAML_MAPPER.convertValue(resourceNode.get(McpSchema.ATTR_ANNOTATIONS), McpSchema.Annotations.class) : null;
         var meta = resourceNode.has(McpSchema.ATTR_META) ?
               YAML_MAPPER.convertValue(resourceNode.get(McpSchema.ATTR_META), new TypeReference<Map<String, Object>>() {}) : null;
         resources.add(new McpSchema.Resource(uri,
               resourceNode.path("name").asText(null),
               resourceNode.path("title").asText(null),
               resourceNode.path("description").asText(null),
               resourceNode.path(RESOURCE_MIME_TYPE).asText(null),
               size, annotations, meta));
      }
      return resources;
   }

   @Override
   public List<McpSchema.ResourceTemplate> listResourceTemplates() {
      // First, check cache to see if we have already loaded templates.
      JsonNode templatesNode = getResourceTemplatesNode();
      if (templatesNode == null) {
         logger.debugf("No Resources artifact found for service '%s'", service.id());
         return Collections.emptyList();
      }

      // Convert to list of ResourceTemplate objects.
      List<McpSchema.ResourceTemplate> templates = new ArrayList<>();
      Iterator<String> uriTemplatesIterator = templatesNode.fieldNames();
      while (uriTemplatesIterator.hasNext()) {
         String uriTemplate = uriTemplatesIterator.next();

         JsonNode templateNode = templatesNode.get(uriTemplate);
         var annotations = templatesNode.has(McpSchema.ATTR_ANNOTATIONS) ?
               YAML_MAPPER.convertValue(templatesNode.get(McpSchema.ATTR_ANNOTATIONS), McpSchema.Annotations.class) : null;
         templates.add(new McpSchema.ResourceTemplate(uriTemplate,
               templateNode.path("name").asText(null),
               templateNode.path("title").asText(null),
               templateNode.path("description").asText(null),
               templateNode.path(RESOURCE_MIME_TYPE).asText(null),
               annotations));
      }
      return templates;
   }

   @Override
   @Nullable
   public List<McpSchema.ResourceContents> readResource(McpSchema.ReadResourceRequest request, ConfigurationEntry configuration) {
      JsonNode resourceNode = null;

      // First, check cache to see if we have already loaded resources.
      JsonNode resourcesNode = getResourcesNode();
      if (resourcesNode != null) {
         // Check direct resource first.
         resourceNode = resourcesNode.get(request.uri());
      }

      if (resourceNode == null) {
         // No direct resource found, look for templates.
         logger.debugf("No direct resource '%s' found for service '%s', looking templates", request.uri(), service.id());

         JsonNode templatesNode = getResourceTemplatesNode();
         if (templatesNode == null) {
            logger.debugf("No Resources artifact found for service '%s'", service.id());
            return null;
         }

         // Find for resource as an instantiation of a template.
         Iterator<String> uriTemplatesIterator = templatesNode.fieldNames();
         while (uriTemplatesIterator.hasNext()) {
            String uriTemplate = uriTemplatesIterator.next();
            if (matchesUriTemplate(uriTemplate, request.uri())) {
               resourceNode = templatesNode.get(uriTemplate);
               break;
            }
         }
      }

      // Find the requested resource.
      if (resourceNode == null) {
         logger.debugf("No resource with URI '%s' found for service '%s'", request.uri(), service.id());
         return null;
      }

      // Build the resource read result contents.
      List<McpSchema.ResourceContents> results = new ArrayList<>();

      // It may be directly specified in the artifact as text or blob.
      if (resourceNode.has("text")) {
         results.add(new McpSchema.TextResourceContents(request.uri(),
               resourceNode.path(RESOURCE_MIME_TYPE).asText(TEXT_PLAIN_MIME_TYPE),
               resourceNode.get("text").asText()));
      } else if (resourceNode.has("blob")) {
         results.add(new McpSchema.BlobResourceContents(request.uri(),
               resourceNode.path(RESOURCE_MIME_TYPE).asText(APPLICATION_OCTET_STREAM_MIME_TYPE),
               resourceNode.get("blob").asText()));
      } else {
         fetchResource(request, configuration, resourceNode, results);
      }

      return results;
   }

   /** Fetch the resource content from the backend URI, either a file:// or ui:// resource. */
   private void fetchResource(McpSchema.ReadResourceRequest request, ConfigurationEntry configuration, JsonNode resourceNode, List<McpSchema.ResourceContents> results) {
      // We need to fetch the resource content from a backend URI.
      if (request.uri().startsWith("file:///")) {
         String backendUrl = configuration.backendEndpoint() + request.uri().substring("file://".length());

         BackendResponse fetchResponse = proxyService.callBackend(configuration, URI.create(backendUrl),
               "GET", Collections.emptyMap(), null);

         // Check it's text or blob based on mime type.
         String contentType = fetchResponse.headers().getOrDefault(HttpHeaders.CONTENT_TYPE, List.of()).stream().findFirst().orElse(null);
         if (contentType == null) {
            contentType = resourceNode.path(RESOURCE_MIME_TYPE).asText(APPLICATION_OCTET_STREAM_MIME_TYPE);
         }
         if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("yaml") || contentType.contains("xml") ) {
            String fetchedContent = null;
            try {
               fetchedContent = ContentUtil.extractResponseContent(fetchResponse);
            } catch (Exception e) {
               logger.errorf(e, "Cannot extract text content for resource '%s' from backend", request.uri());
               results.add(new McpSchema.TextResourceContents(request.uri(), TEXT_PLAIN_MIME_TYPE,
                     "Error extracting resource content: " + e.getMessage()));
            }
            results.add(new McpSchema.TextResourceContents(request.uri(), contentType, fetchedContent));
         } else {
            results.add(new McpSchema.BlobResourceContents(request.uri(), contentType, Base64.encode(fetchResponse.content())));
         }
      } else if (request.uri().startsWith("ui://") && resourceNode.has("remoteContent")) {
         fetchRemoteContent(request, configuration, resourceNode, results);
      }
   }

   /** Fetch the resource content from a different backend URI found into remoteContent. */
   private void fetchRemoteContent(McpSchema.ReadResourceRequest request, ConfigurationEntry configuration, JsonNode resourceNode, List<McpSchema.ResourceContents> results) {
      // Special handling for ui:// resources - they are supposed to be served from another server found in the remoteResource property.
      String remoteResourceUrl = resourceNode.path("remoteContent").asText(null);
      if (remoteResourceUrl == null) {
         logger.debugf("No remoteContent property found for ui:// resource '%s' for service '%s'", request.uri(), service.id());
         results.add(new McpSchema.TextResourceContents(request.uri(), TEXT_PLAIN_MIME_TYPE,
               "No remoteContent property found for ui:// resource '" + request.uri() + "'."));
      } else {
         // Fetch from remoteContent URL using declared remoteSecret (or unauthenticated when absent/unresolved).
         SecretEntry remoteSecret = resolveRemoteSecret(request.uri(), resourceNode);
         BackendResponse fetchResponse = proxyService.callBackend(configuration, remoteSecret, URI.create(remoteResourceUrl),
               "GET", Collections.emptyMap(), null);

         Map<String, Object> meta = resourceNode.has(McpSchema.ATTR_META) ?
               YAML_MAPPER.convertValue(resourceNode.get(McpSchema.ATTR_META), new TypeReference<Map<String, Object>>() {}) : null;
         results.add(new McpSchema.TextResourceContents(request.uri(), "text/html;profile=mcp-app",
               new String(fetchResponse.content(), StandardCharsets.UTF_8), meta));
      }
   }

   private @Nullable JsonNode getResourcesNode() {
      return mergeAcrossArtifacts("resources", CACHE_KEYS_PREFIX);
   }

   private @Nullable JsonNode getResourceTemplatesNode() {
      return mergeAcrossArtifacts("resourceTemplates", CACHE_KEYS_PREFIX + "-templates");
   }

   /**
    * Resolve the {@link SecretEntry} declared by {@code remoteSecret} on {@code resourceNode} against
    * the owning Resources artifact's {@code relatedSecrets}. Checks direct {@code resources} first and
    * falls back to {@code resourceTemplates}, mirroring {@link #readResource}. Returns {@code null}
    * (and logs a warning when a secret name was declared) if it cannot be resolved.
    */
   private @Nullable SecretEntry resolveRemoteSecret(String uri, JsonNode resourceNode) {
      String remoteSecretName = resourceNode.path("remoteSecret").asText(null);
      if (remoteSecretName == null || remoteSecretName.isBlank() || attachedArtifacts == null) {
         return null;
      }
      for (ArtifactEntry artifact : attachedArtifacts) {
         if (!ArtifactEntryType.RESHAPR_RESOURCES.equals(artifact.type())) {
            continue;
         }
         SecretEntry secret = artifact.relatedSecrets().get(remoteSecretName);
         if (secret != null) {
            return secret;
         }
      }
      logger.warnf("Remote secret '%s' referenced by resource '%s' of service '%s' could not be resolved; proceeding unauthenticated",
            remoteSecretName, uri, service.id());
      return null;
   }

   private static boolean matchesUriTemplate(String uriTemplate, String uri) {
      String uriPattern = uriTemplate.replace("/", "\\/")   // Escape slashes.
            .replace("?", "\\?")                            // Escape query parameters marker.
            .replaceAll("\\{(.*)\\}", "(.*)");              // Prepare groups for extraction.
      return uri.matches(uriPattern);
   }

   /**
    * Merge a given root sub-node ({@code resources} or {@code resourceTemplates}) across <b>all</b> attached
    * Resources artifacts. Each artifact is parsed and cached individually (keyed by its id); the entries are
    * merged by uri (first declaring artifact wins on collision, deterministic by attachment order). Returns
    * null when no attached Resources artifact declares any entry.
    */
   private @Nullable JsonNode mergeAcrossArtifacts(String jsonKey, String minor) {
      if (attachedArtifacts == null || attachedArtifacts.isEmpty()) {
         return null;
      }
      ObjectNode merged = YAML_MAPPER.createObjectNode();
      for (ArtifactEntry artifact : attachedArtifacts) {
         if (!ArtifactEntryType.RESHAPR_RESOURCES.equals(artifact.type())) {
            continue;
         }
         JsonNode subNode = getSubNodeForArtifact(artifact, jsonKey, minor);
         if (subNode != null && subNode.isObject()) {
            Iterator<String> uris = subNode.fieldNames();
            while (uris.hasNext()) {
               String uri = uris.next();
               if (!merged.has(uri)) {
                  merged.set(uri, subNode.get(uri));
               }
            }
         }
      }
      return merged.isEmpty() ? null : merged;
   }

   /** Parse and cache (keyed by artifact id + minor) a root sub-node of a single Resources artifact. */
   private @Nullable JsonNode getSubNodeForArtifact(ArtifactEntry artifact, String jsonKey, String minor) {
      if (workCache.get(artifact.id(), minor) instanceof JsonNode cached) {
         logger.tracef("Got a cached value of '%s' JsonNode for artifact '%s'", jsonKey, artifact.id());
         return cached;
      }
      try {
         JsonNode artifactNode = YAML_MAPPER.readTree(artifact.content());
         JsonNode subNode = artifactNode.get(jsonKey);
         if (subNode != null) {
            workCache.set(artifact.id(), minor, subNode);
         }
         return subNode;
      } catch (Exception e) {
         logger.errorf(e, "Cannot read Reshapr Resources artifact '%s' for service '%s'", artifact.id(), service.id());
         return null;
      }
   }
}
