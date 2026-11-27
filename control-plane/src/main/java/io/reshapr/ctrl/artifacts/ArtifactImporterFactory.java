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

import io.reshapr.util.ReferenceResolver;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * Factory for building/retrieving artifact importer implementations.
 * @author laurent
 */
public class ArtifactImporterFactory {

   /** Get a JBoss logging logger. */
   private static final Logger log = Logger.getLogger(ArtifactImporterFactory.class);

   /** A RegExp for detecting a line containing the openapi: 3 pragma. */
   public static final String OPENAPI_3_REGEXP = ".*['\\\"]?openapi['\\\"]?\\s*:\\s*['\\\"]?[3\\.].*";

   /** A RegExp for detecting a line containing the asyncapi: 3 pragma. */
   public static final String ASYNCAPI_3_REGEXP = ".*['\\\"]?asyncapi['\\\"]?\\s*:\\s*['\\\"]?3\\..*";

   private static final Pattern OPENAPI_3_PATTERN = Pattern.compile("['\\\"]?openapi['\\\"]?\\s*:\\s*['\\\"]?[3.]");
   private static final Pattern ASYNCAPI_3_PATTERN = Pattern.compile("['\\\"]?asyncapi['\\\"]?\\s*:\\s*['\\\"]?3\\.");

   private ArtifactImporterFactory() {
      // Private constructor to hide the implicit one as it's a utility class.
   }

   /**
    * Create the right ArtifactImporter implementation depending on repository type.
    * @param artifactFile    The file representing the artifact
    * @param referenceResolver The Resolver to be used during import (may be null).
    * @return An instance of ArtifactImporter implementation
    * @throws IOException in case of file access
    */
   public static ArtifactImporter getArtifactImporter(File artifactFile, ReferenceResolver referenceResolver) throws IOException {
      ArtifactImporter importer = null;

      // Analyse first lines of file content to guess repository type.
      String line = null;
      try (BufferedReader reader = Files.newBufferedReader(artifactFile.toPath(), StandardCharsets.UTF_8)) {
         while ((line = reader.readLine()) != null && importer == null) {
            line = line.trim();
            // Try OpenAPI related one...
            importer = checkOpenAPIImporters(line, artifactFile, referenceResolver);
            // Then try any other else.
            if (importer == null) {
               importer = checkOtherImporters(line, artifactFile, referenceResolver);
            }
         }
      }
      return importer;
   }

   private static ArtifactImporter checkOpenAPIImporters(String line, File artifactFile,
                                                         ReferenceResolver referenceResolver) throws IOException {
      if (OPENAPI_3_PATTERN.matcher(line).find()) {
         log.info("Found an openapi: 3 pragma in file so assuming it's an OpenAPI spec to import");
         return new OpenAPIImporter(artifactFile.getPath(), referenceResolver);
      }
      return null;
   }

   private static ArtifactImporter checkOtherImporters(String line, File artifactFile, ReferenceResolver referenceResolver) throws IOException {
      if (ASYNCAPI_3_PATTERN.matcher(line).find()) {
         log.info("Found an asyncapi: 3 pragma in file so assuming it's an AsyncAPI spec to import");
         return new AsyncAPIImporter(artifactFile.getPath(), referenceResolver);
      } else if (line.startsWith("syntax = \"proto3\";") || line.startsWith("syntax=\"proto3\";")) {
         log.info("Found a syntax = proto3 pragma in file so assuming it's a GRPC Protobuf spec to import");
         return new ProtobufImporter(artifactFile.getPath(), referenceResolver);
      } else if (line.startsWith("schema {") || line.contains("type Query {")
            || line.contains("type Query implements ") || line.contains("type Mutation {")
            || line.startsWith("# microcksId:")) {
         log.info("Found query, mutation or microcksId: pragma in file so assuming it's a GraphQL schema to import");
         return new GraphQLImporter(artifactFile.getPath());
      }
      return null;
   }
}
