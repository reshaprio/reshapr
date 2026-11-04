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
package io.reshapr.config;

/**
 * Defines constants for Reshapr artifact schemas.
 * @author laurent
 */
public interface ReshaprArtifactSchemas {

   String PROMPTS_KIND = "Prompts";
   String PROMPTS_VERSION_V1ALPHA1 = "reshapr.io/v1alpha1";

   String RESOURCES_KIND = "Resources";
   String RESOURCES_VERSION_V1ALPHA1 = "reshapr.io/v1alpha1";

   String CUSTOM_TOOLS_KIND = "CustomTools";
   String CUSTOM_TOOLS_VERSION_V1ALPHA1 = "reshapr.io/v1alpha1";

   String TOOLS_OUTPUT_FILTERS_KIND = "ToolsOutputFilters";
   String TOOLS_OUTPUT_FILTERS_VERSION_V1ALPHA1 = "reshapr.io/v1alpha1";

   String SKILLS_KIND = "Skills";
   String SKILLS_VERSION_V1ALPHA1 = "reshapr.io/v1alpha1";
}
