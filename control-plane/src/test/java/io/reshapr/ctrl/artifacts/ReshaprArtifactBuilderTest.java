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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ReshaprArtifactBuilder.
 * @author laurent
 */
class ReshaprArtifactBuilderTest {

   @Test
   void testMissingKind() {
      URL promptsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/prompts-missing-kind.yaml");
      File promptsFile = new File(promptsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("prompts-missing-kind.yaml", promptsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact is missing required 'apiVersion' and/or 'kind' fields", mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for missing kind.");
   }

   @Test
   void testUnsupportedArtifactKind() {
      URL promptsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/prompts-unsupported-kind.yaml");
      File promptsFile = new File(promptsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("prompts-unsupported-kind.yaml", promptsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Unsupported artifact kind and version: XPrompts - reshapr.io/v1alpha1",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for unsupported artifact kind.");
   }

   @Test
   void testNonConformantPrompts() {
      URL promptsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/prompts-invalid.yaml");
      File promptsFile = new File(promptsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("prompts-invalid.yaml", promptsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'Prompts' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for non-conformant prompts.");
   }

   @Test
   void testValidParsingOfPrompts() {
      URL promptsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/prompts-valid.yaml");
      File promptsFile = new File(promptsURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("prompts-valid.yaml", promptsFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_PROMPTS, artifact.type);
      assertEquals("prompts-valid.yaml", artifact.name);
      assertEquals("prompts-valid.yaml", artifact.sourceArtifact);
      assertEquals("Pastry API", artifactWithServiceRef.serviceName());
      assertEquals("2.0.0", artifactWithServiceRef.serviceVersion());
      // Capabilities are the prompt names, in declaration order.
      assertEquals(List.of("list_pastries", "get_pastry"), artifact.capabilities);
   }

   @Test
   void testNonConformantCustomTools() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/custom-tools-invalid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("custom-tools-invalid.yaml", customToolsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'CustomTools' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for non-conformant prompts.");
   }

   @Test
   void testNonConformantCustomToolsMissingArguments() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/custom-tools-no-arguments-invalid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("custom-tools-no-arguments-invalid.yaml", customToolsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'CustomTools' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for a declarative custom tool missing 'arguments'.");
   }

   @Test
   void testNonConformantCustomToolsUnknownPlaceholder() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/custom-tools-unknown-placeholder-invalid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("custom-tools-unknown-placeholder-invalid.yaml", customToolsFile);
      } catch (ReshaprArtifactException mae) {
         assertTrue(mae.getMessage().contains("get_user_with_latest_followers"), mae.getMessage());
         assertTrue(mae.getMessage().contains("${input.user}"), mae.getMessage());
         assertTrue(mae.getMessage().contains("declared inputs are: user"), mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for a declarative custom tool referencing an undeclared input placeholder.");
   }

   @Test
   void testValidParsingCustomTools() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/custom-tools-valid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("custom-tools-valid.yaml", customToolsFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_CUSTOM_TOOLS, artifact.type);
      assertEquals("custom-tools-valid.yaml", artifact.name);
      assertEquals("custom-tools-valid.yaml", artifact.sourceArtifact);
      assertEquals("GitHub GraphQL", artifactWithServiceRef.serviceName());
      assertEquals("20250917", artifactWithServiceRef.serviceVersion());
      // Capabilities are the custom tool names.
      assertEquals(List.of("get_user_with_latest_followers"), artifact.capabilities);
   }

   @Test
   void testValidParsingScriptCustomTools() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/custom-tools-script-valid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("custom-tools-script-valid.yaml", customToolsFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_CUSTOM_TOOLS, artifact.type);
      assertEquals("custom-tools-script-valid.yaml", artifact.name);
      assertEquals("custom-tools-script-valid.yaml", artifact.sourceArtifact);
      assertEquals("GitHub GraphQL", artifactWithServiceRef.serviceName());
      assertEquals("20250917", artifactWithServiceRef.serviceVersion());
      // Capabilities are the custom tool names (script form).
      assertEquals(List.of("get_user_with_followers_and_issues"), artifact.capabilities);
   }

   @Test
   void testNonConformantScriptCustomTools() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/custom-tools-script-invalid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("custom-tools-script-invalid.yaml", customToolsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'CustomTools' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for a script custom tool missing the 'tools' allow-list.");
   }

   @Test
   void testNonConformantResources() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/resources-invalid.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("resources-invalid.yaml", customToolsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'Resources' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for non-conformant prompts.");
   }

   @Test
   void testNonConformantResourcesWithTwoContents() {
      URL customToolsURL = getClass().getResource("/io/reshapr/ctrl/artifacts/resources-invalid-two-contents.yaml");
      File customToolsFile = new File(customToolsURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("resources-invalid-two-contents.yaml", customToolsFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'Resources' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for non-conformant prompts.");
   }

   @Test
   void testValidParsingResources() {
      URL resourcesURL = getClass().getResource("/io/reshapr/ctrl/artifacts/resources-valid.yaml");
      File resourcesFile = new File(resourcesURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("resources-valid.yaml", resourcesFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_RESOURCES, artifact.type);
      assertEquals("resources-valid.yaml", artifact.name);
      assertEquals("resources-valid.yaml", artifact.sourceArtifact);
      assertEquals("Repository Service", artifactWithServiceRef.serviceName());
      assertEquals("1.0.0", artifactWithServiceRef.serviceVersion());
      // Capabilities are the resource uris (the object keys).
      assertEquals(List.of("file:///project/src/main.rs"), artifact.capabilities);
   }

   @Test
   void testValidParsingResourceTemplates() {
      URL resourcesURL = getClass().getResource("/io/reshapr/ctrl/artifacts/resources-templates-valid.yaml");
      File resourcesFile = new File(resourcesURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("resources-templates-valid.yaml", resourcesFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_RESOURCES, artifact.type);
      assertEquals("resources-templates-valid.yaml", artifact.name);
      assertEquals("resources-templates-valid.yaml", artifact.sourceArtifact);
      assertEquals("Repository Service", artifactWithServiceRef.serviceName());
      assertEquals("1.0.0", artifactWithServiceRef.serviceVersion());
      // Capabilities include the resourceTemplates uris.
      assertEquals(List.of("file:///project/src/{path}?mode=raw"), artifact.capabilities);
   }

   @Test
   void testValidParsingAllResources() {
      URL resourcesURL = getClass().getResource("/io/reshapr/ctrl/artifacts/resources-all-valid.yaml");
      File resourcesFile = new File(resourcesURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("resources-all-valid.yaml", resourcesFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_RESOURCES, artifact.type);
      assertEquals("resources-all-valid.yaml", artifact.name);
      assertEquals("resources-all-valid.yaml", artifact.sourceArtifact);
      assertEquals("Repository Service", artifactWithServiceRef.serviceName());
      assertEquals("1.0.0", artifactWithServiceRef.serviceVersion());
      // Capabilities aggregate resources then resourceTemplates uris, in declaration order.
      assertEquals(List.of(
            "file:///favicon.ico",
            "file:///project/src/main.rs",
            "file:///project/src/{path}?mode=raw"), artifact.capabilities);
   }

   @Test
   void testNonConformantToolsOutputFilters() {
      URL filtersURL = getClass().getResource("/io/reshapr/ctrl/artifacts/tools-output-filters-invalid.yaml");
      File filtersFile = new File(filtersURL.getFile());

      try {
         ReshaprArtifactBuilder.parseArtifact("tools-output-filters-invalid.yaml", filtersFile);
      } catch (ReshaprArtifactException mae) {
         assertEquals("Artifact content is not valid against schema for kind 'ToolsOutputFilters' and version 'reshapr.io/v1alpha1'",
               mae.getMessage());
         return;
      }
      fail("An exception should have been thrown for non-conformant tools output filters.");
   }

   @Test
   void testValidParsingToolsOutputFilters() {
      URL filtersURL = getClass().getResource("/io/reshapr/ctrl/artifacts/tools-output-filters-valid.yaml");
      File filtersFile = new File(filtersURL.getFile());

      ReshaprArtifactBuilder.ArtifactWithServiceRef artifactWithServiceRef = null;
      try {
         artifactWithServiceRef = ReshaprArtifactBuilder.parseArtifact("tools-output-filters-valid.yaml", filtersFile);
      } catch (Exception e) {
         fail("Exception should not have been thrown: " + e.getMessage());
      }

      assertNotNull(artifactWithServiceRef);

      Artifact artifact = artifactWithServiceRef.artifact();
      assertEquals(ArtifactType.RESHAPR_TOOLS_OUTPUT_FILTERS, artifact.type);
      assertEquals("tools-output-filters-valid.yaml", artifact.name);
      assertEquals("tools-output-filters-valid.yaml", artifact.sourceArtifact);
      assertEquals("Pastry API", artifactWithServiceRef.serviceName());
      assertEquals("2.0.0", artifactWithServiceRef.serviceVersion());
      // Capabilities are the filtered tool names.
      assertEquals(List.of("listPastries", "getPastry"), artifact.capabilities);
   }
}
