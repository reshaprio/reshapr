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
package io.reshapr.ctrl.model;

/**
 * An enumeration of the different types of artifacts that can be managed by the application.
 * @author laurent
 */
public enum ArtifactType {
   JSON_SCHEMA,
   OPEN_API_SPEC,
   GRAPHQL_SCHEMA,
   PROTOBUF_SCHEMA,
   PROTOBUF_DESCRIPTOR,
   JSON_FRAGMENT,
   RESHAPR_PROMPTS,
   RESHAPR_CUSTOM_TOOLS,
   RESHAPR_RESOURCES,
   RESHAPR_TOOLS_OUTPUT_FILTERS,
   RESHAPR_SKILLS
}
