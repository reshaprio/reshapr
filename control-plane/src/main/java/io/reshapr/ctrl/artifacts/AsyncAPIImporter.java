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
package io.reshapr.ctrl.artifacts;

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.util.ReferenceResolver;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/**
 * An implementation of ArtifactImporter that deals with AsyncAPI v3.x specification file ; whether encoded into
 * JSON or YAML documents. Operations are mapped from AsyncAPI operations: {@code method} holds the AsyncAPI
 * {@code action} (SEND when the application emits events, RECEIVE when it receives them), {@code action} holds
 * the channel address, and input/output names hold the first message name of the operation.
 * @author laurent
 */
public class AsyncAPIImporter extends AbstractJsonArtifactImporter implements ArtifactImporter {

   /** The AsyncAPI actions that are supported. */
   public static final String ACTION_SEND = "SEND";
   public static final String ACTION_RECEIVE = "RECEIVE";

   private static final String INTERNAL_REF_PREFIX = "#/";

   protected String serviceName;
   protected String serviceVersion;

   /**
    * Build a new importer.
    * @param specificationFilePath The path to local AsyncAPI spec file
    * @param referenceResolver     An optional resolver for references present into the AsyncAPI file
    * @throws IOException if project file cannot be found or read.
    */
   public AsyncAPIImporter(String specificationFilePath, ReferenceResolver referenceResolver) throws IOException {
      super(specificationFilePath, referenceResolver);
   }

   @Override
   public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
   }

   @Override
   public void setServiceVersion(String serviceVersion) {
      this.serviceVersion = serviceVersion;
   }

   @Override
   public List<Service> getServiceDefinitions() throws ArtifactImportException {
      String version = rootSpecification.path("asyncapi").asText("");
      if (!version.startsWith("3.")) {
         throw new ArtifactImportException("Only AsyncAPI 3.x documents are supported but found '" + version + "'");
      }

      Service service = new Service();
      service.name = serviceName != null ? serviceName : rootSpecification.path("info").path("title").asText();
      service.version = serviceVersion != null ? serviceVersion
            : rootSpecification.path("info").path("version").asText();
      service.type = ServiceType.ASYNC;

      // Before extracting operations, get and build external references if we have a resolver.
      initializeReferencedArtifacts(service);

      service.operations = extractOperations();
      return List.of(service);
   }

   @Override
   public List<Artifact> getArtifactDefinitions(Service service) {
      List<Artifact> results = new ArrayList<>();

      Artifact resource = new Artifact();
      resource.name = service.name + "-" + service.version + (Boolean.TRUE.equals(isYaml) ? ".yaml" : ".json");
      resource.type = ArtifactType.ASYNC_API_SPEC;
      resource.content = rootSpecificationContent;
      results.add(resource);

      results.addAll(externalArtifacts);
      return results;
   }

   /** Extract the operations from the AsyncAPI {@code operations} node. */
   private List<Operation> extractOperations() throws ArtifactImportException {
      List<Operation> results = new ArrayList<>();

      for (Entry<String, JsonNode> entry : rootSpecification.path("operations").properties()) {
         JsonNode operationNode = entry.getValue();
         String action = operationNode.path("action").asText("").toUpperCase();
         if (!ACTION_SEND.equals(action) && !ACTION_RECEIVE.equals(action)) {
            throw new ArtifactImportException("Operation '" + entry.getKey() + "' has an invalid action '"
                  + action + "' (expected send or receive)");
         }

         String channelKey = resolveChannelKey(operationNode.path("channel"));
         if (channelKey == null || !rootSpecification.path("channels").has(channelKey)) {
            throw new ArtifactImportException("Operation '" + entry.getKey() + "' does not reference a known channel");
         }
         JsonNode channelNode = rootSpecification.path("channels").path(channelKey);

         Operation operation = new Operation();
         operation.name = entry.getKey();
         operation.method = action;
         operation.action = channelNode.path("address").asText(channelKey);

         String messageName = firstMessageName(operationNode, channelNode);
         if (messageName != null) {
            operation.inputName = messageName;
            operation.outputName = messageName;
         }
         results.add(operation);
      }
      return results;
   }

   /** Resolve the channels key from an operation channel reference such as {@code #/channels/orders}. */
   private String resolveChannelKey(JsonNode channelRef) {
      String ref = channelRef.path("$ref").asText(null);
      if (ref == null || !ref.startsWith(INTERNAL_REF_PREFIX + "channels/")) {
         return null;
      }
      return ref.substring((INTERNAL_REF_PREFIX + "channels/").length());
   }

   /** Get the name of the first message of the operation (or channel) if any. */
   private String firstMessageName(JsonNode operationNode, JsonNode channelNode) {
      JsonNode messages = operationNode.path("messages");
      if (messages.isArray() && !messages.isEmpty()) {
         String ref = messages.get(0).path("$ref").asText(null);
         if (ref != null) {
            return ref.substring(ref.lastIndexOf('/') + 1);
         }
      }
      var channelMessages = channelNode.path("messages").properties();
      return channelMessages.isEmpty() ? null : channelMessages.iterator().next().getKey();
   }
}
