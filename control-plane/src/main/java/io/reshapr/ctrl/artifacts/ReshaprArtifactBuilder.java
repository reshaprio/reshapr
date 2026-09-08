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
import io.reshapr.json.ObjectMapperFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A utility class for building and validating Reshapr specific artifacts.
 * We're using JSON Schema validation provided by networknt library.
 * @author laurent
 */
public class ReshaprArtifactBuilder {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(ReshaprArtifactBuilder.class);

   private static final Map<String, String> KIND_VERSIONS_SCHEMAS = Map.of(
         "Prompts-reshapr.io/v1alpha1", "/schemas/Prompts-v1alpha1-schema.json",
         "CustomTools-reshapr.io/v1alpha1", "/schemas/CustomTools-v1alpha1-schema.json",
         "Resources-reshapr.io/v1alpha1", "/schemas/Resources-v1alpha1-schema.json",
         "ToolsOutputFilters-reshapr.io/v1alpha1", "/schemas/ToolsOutputFilters-v1alpha1-schema.json"
   );

   private static final Map<String, ArtifactType> KIND_VERSIONS_TYPES = Map.of(
         "Prompts-reshapr.io/v1alpha1", ArtifactType.RESHAPR_PROMPTS,
         "CustomTools-reshapr.io/v1alpha1", ArtifactType.RESHAPR_CUSTOM_TOOLS,
         "Resources-reshapr.io/v1alpha1", ArtifactType.RESHAPR_RESOURCES,
         "ToolsOutputFilters-reshapr.io/v1alpha1", ArtifactType.RESHAPR_TOOLS_OUTPUT_FILTERS
   );

   private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper()
         .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
         .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).enable(SerializationFeature.INDENT_OUTPUT);


   private ReshaprArtifactBuilder() {
      // Hide constructor for this utility class.
   }

   /** A thin wrapper around Artifact model and basic service name and version information. */
   public record ArtifactWithServiceRef(Artifact artifact, String serviceName, String serviceVersion) {
   }

   /**
    * Parse and validate a Reshapr artifact from a file.
    * @param name the name to assign to the artifact
    * @param artifactFile the artifact file
    * @return an ArtifactWithServiceRef containing the parsed Artifact and service references
    * @throws ReshaprArtifactException in case of parsing or validation errors
    */
   public static ArtifactWithServiceRef parseArtifact(String name, File artifactFile) throws ReshaprArtifactException {
      // Initialize an empty Artifact.
      Artifact artifact = new Artifact();
      artifact.name = name;
      artifact.sourceArtifact = name;

      // Assume YAML by default.
      boolean isYaml = true;

      try (BufferedReader reader = new BufferedReader(new FileReader(artifactFile))) {
         StringBuilder contentBuilder = new StringBuilder();

         String line;
         int lineNumber = 0;
         // We must go through the file line by line to determine if it's JSON or YAML.
         while ((line = reader.readLine()) != null) {
            // Only treat as JSON if the very first line starts with { or [
            if (lineNumber == 0 && (line.startsWith("{") || line.startsWith("["))) {
               isYaml = false;
            }
            contentBuilder.append(line).append("\n");
            lineNumber++;
         }

         artifact.content = contentBuilder.toString();
      } catch (Exception e) {
         logger.error("Error reading artifact file", e);
         throw new ReshaprArtifactException("Error reading artifact file: " + e.getMessage(), e);
      }

      // Find the appropriate ObjectMapper.
      ObjectMapper mapper = isYaml ? ObjectMapperFactory.getYamlObjectMapper() : ObjectMapperFactory.getJsonObjectMapper();
      JsonNode artifactNode;
      try {
         artifactNode = mapper.readTree(artifact.content);
      } catch (Exception e) {
         logger.error("Error parsing artifact content", e);
         throw new ReshaprArtifactException("Error parsing artifact content: " + e.getMessage(), e);
      }

      // Checking for required fields: apiVersion and kind.
      String apiVersion = artifactNode.path("apiVersion").asText();
      String kind = artifactNode.path("kind").asText();
      if (apiVersion.isBlank() || kind.isBlank()) {
         throw new ReshaprArtifactException("Artifact is missing required 'apiVersion' and/or 'kind' fields");
      }

      // Check if we support these kind and version.
      if (!KIND_VERSIONS_SCHEMAS.containsKey(kind + "-" + apiVersion)) {
         throw new ReshaprArtifactException("Unsupported artifact kind and version: " + kind + " - " + apiVersion);
      }

      // Now validate against the schema.
      boolean isValid = false;
      try {
         isValid = isJsonValid(KIND_VERSIONS_SCHEMAS.get(kind + "-" + apiVersion), artifactNode);
      } catch (Exception e) {
         logger.error("Error validating artifact content against schema", e);
         throw new ReshaprArtifactException("Error validating artifact content against schema: " + e.getMessage(), e);
      }
      if (!isValid) {
         throw new ReshaprArtifactException("Artifact content is not valid against schema for kind '" + kind +
               "' and version '" + apiVersion + "'");
      }

      // Set the type from the kind-version map.
      artifact.type = KIND_VERSIONS_TYPES.get(kind + "-" + apiVersion);

      // Additional semantic validations that JSON Schema alone cannot express.
      if (artifact.type == ArtifactType.RESHAPR_CUSTOM_TOOLS) {
         validateCustomToolsPlaceholders(artifactNode);
      }

      // Extract the capabilities (element names) declared by this custom artifact.
      artifact.capabilities = extractCapabilities(artifact.type, artifactNode);

      // Finally, extract service name and version to populate Artifact.
      JsonNode serviceNode = artifactNode.get("service");

      return new ArtifactWithServiceRef(artifact, serviceNode.get("name").asText(),
            serviceNode.get("version").asText());
   }

   /**
    * Extract the capabilities (element names) declared by a custom artifact from its parsed content.
    * Capabilities are the keys of the type-specific object: prompt names for Prompts, custom tool names
    * for CustomTools, resource/resourceTemplate uris for Resources and filtered tool names for
    * ToolsOutputFilters. The extraction is defensive: any missing/malformed sub-node yields an empty list
    * so it never blocks an attachment (the content has already been validated against the schema).
    * @param type the resolved artifact type
    * @param artifactNode the parsed artifact content
    * @return an ordered, de-duplicated list of capability names (never null)
    */
   private static List<String> extractCapabilities(ArtifactType type, JsonNode artifactNode) {
      if (type == null || artifactNode == null) {
         return List.of();
      }

      // Preserve declaration order and de-duplicate names.
      Set<String> capabilities = new LinkedHashSet<>();
      switch (type) {
         case RESHAPR_PROMPTS -> collectFieldNames(artifactNode.get("prompts"), capabilities);
         case RESHAPR_CUSTOM_TOOLS -> collectFieldNames(artifactNode.get("customTools"), capabilities);
         case RESHAPR_RESOURCES -> {
            collectFieldNames(artifactNode.get("resources"), capabilities);
            collectFieldNames(artifactNode.get("resourceTemplates"), capabilities);
         }
         case RESHAPR_TOOLS_OUTPUT_FILTERS -> collectFieldNames(artifactNode.get("filters"), capabilities);
         default -> {
            // Non-custom artifact: no capabilities to extract.
         }
      }
      return List.copyOf(capabilities);
   }

   /** Add the field names of the given node (if it is an object) to the target set. */
   private static void collectFieldNames(JsonNode node, Set<String> target) {
      if (node != null && node.isObject()) {
         node.fieldNames().forEachRemaining(target::add);
      }
   }

   /** Matches a scalar that is exactly a single {@code ${var}} placeholder (whitespace tolerant). */
   private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\s*\\$\\{([^}]+)\\}\\s*$");

   /**
    * Validate that every {@code ${var}} placeholder used in the {@code arguments} of a declarative
    * (non-script) custom tool references a property declared under that tool's {@code input}. The proxy
    * only substitutes a value when the whole scalar is exactly {@code ${var}} and silently drops any
    * unresolved variable, which makes typos (e.g. {@code ${input.property}} instead of {@code ${property}})
    * very hard to diagnose. We therefore reject such artifacts at import time with a precise message. This
    * mirrors the editor-side {@code custom-tools-placeholders} validator in the web UI.
    * @param artifactNode the parsed CustomTools artifact content
    * @throws ReshaprArtifactException when an argument references an input that is not declared
    */
   private static void validateCustomToolsPlaceholders(JsonNode artifactNode) throws ReshaprArtifactException {
      JsonNode customTools = artifactNode.get("customTools");
      if (customTools == null || !customTools.isObject()) {
         return;
      }

      Iterator<Map.Entry<String, JsonNode>> tools = customTools.fields();
      while (tools.hasNext()) {
         Map.Entry<String, JsonNode> toolEntry = tools.next();
         JsonNode tool = toolEntry.getValue();
         // Only declarative tools carry an `arguments` template; script tools are handled differently.
         if (tool == null || !tool.isObject() || tool.has("script")) {
            continue;
         }
         JsonNode arguments = tool.get("arguments");
         if (arguments == null) {
            continue;
         }

         Set<String> declared = collectInputVarPaths(tool.get("input"));
         Set<String> unresolved = new LinkedHashSet<>();
         collectUnresolvedPlaceholders(arguments, declared, unresolved);

         if (!unresolved.isEmpty()) {
            String declaredList = declared.isEmpty() ? "none" : String.join(", ", declared);
            throw new ReshaprArtifactException("Custom tool '" + toolEntry.getKey()
                  + "' references undeclared input placeholder(s) " + String.join(", ", unresolved)
                  + " in its 'arguments'; declared inputs are: " + declaredList);
         }
      }
   }

   /**
    * Collect the dot-joined property paths declared under an {@code input} JSON-schema node, recursing into
    * nested object properties (e.g. {@code parent}, {@code parent.child}).
    * @param inputNode the {@code input} node of a custom tool (may be null)
    * @return the set of declared variable paths (never null)
    */
   private static Set<String> collectInputVarPaths(JsonNode inputNode) {
      Set<String> paths = new LinkedHashSet<>();
      if (inputNode == null || !inputNode.isObject()) {
         return paths;
      }
      collectInputVarPaths(inputNode.get("properties"), "", paths);
      return paths;
   }

   /** Recursive helper collecting declared property paths from a JSON-schema {@code properties} node. */
   private static void collectInputVarPaths(JsonNode propertiesNode, String prefix, Set<String> paths) {
      if (propertiesNode == null || !propertiesNode.isObject()) {
         return;
      }
      for (Map.Entry<String, JsonNode> property : propertiesNode.properties()) {
         String path = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
         paths.add(path);
         JsonNode child = property.getValue();
         if (child != null && child.isObject()) {
            collectInputVarPaths(child.get("properties"), path, paths);
         }
      }
   }

   /** Walk every scalar leaf under an {@code arguments} node, collecting unresolved {@code ${var}} names. */
   private static void collectUnresolvedPlaceholders(JsonNode node, Set<String> declared, Set<String> unresolved) {
      if (node == null) {
         return;
      }
      if (node.isObject()) {
         node.forEach(child -> collectUnresolvedPlaceholders(child, declared, unresolved));
      } else if (node.isArray()) {
         node.forEach(child -> collectUnresolvedPlaceholders(child, declared, unresolved));
      } else if (node.isTextual()) {
         Matcher matcher = WHOLE_PLACEHOLDER.matcher(node.textValue());
         if (matcher.matches()) {
            String name = matcher.group(1).trim();
            if (!declared.contains(name)) {
               unresolved.add("'${" + name + "}'");
            }
         }
      }
   }

   /** Validate the jsonNode against the JSON schema located as schemaResource. */
   private static boolean isJsonValid(String schemaResource, JsonNode jsonNode) throws Exception {
      String schemaContent = getSchemaResourceAsString(schemaResource);
      final JsonSchema jsonSchemaNode = extractJsonSchemaNode(SCHEMA_MAPPER.readTree(schemaContent), null);

      Set<ValidationMessage> messages = jsonSchemaNode.validate(jsonNode, executionContext -> {
         executionContext.getExecutionConfig().setFormatAssertionsEnabled(true);
         executionContext.getExecutionConfig().setLocale(Locale.US);
      });

      if (!messages.isEmpty()) {
         for (ValidationMessage message : messages) {
            logger.error("Schema validation error: " + message.getMessage());
         }
      }

      return messages.isEmpty();
   }

   /** Load schema resource content as string. */
   private static String getSchemaResourceAsString(String schemaResource) throws Exception {
      try (InputStream is = ReshaprArtifactBuilder.class.getResourceAsStream(schemaResource)) {
         if (is == null) {
            throw new Exception("Schema resource not found: " + schemaResource);
         }
         try (Reader reader = new InputStreamReader(is);
              BufferedReader bufferedReader = new BufferedReader(reader)) {
            return bufferedReader.lines().collect(Collectors.joining(System.lineSeparator()));
         }
      }
   }

   /** Extract a JsonSchema node from a JsonNode, possibly using a namespace as base URI. */
   private static JsonSchema extractJsonSchemaNode(JsonNode jsonNode, String namespace) {
      JsonMetaSchema jsonMetaSchema = JsonMetaSchema.builder(JsonMetaSchema.getV202012()).build();
      JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.metaSchema(jsonMetaSchema));

      if (namespace != null) {
         URI baseUri = URI.create(namespace);
         return jsonSchemaFactory.getSchema(baseUri, jsonNode);
      }

      return jsonSchemaFactory.getSchema(jsonNode);
   }
}
