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

/** Mirrors control-plane `ArtifactType`. */
export type ArtifactType =
	| 'JSON_SCHEMA'
	| 'OPEN_API_SPEC'
	| 'GRAPHQL_SCHEMA'
	| 'PROTOBUF_SCHEMA'
	| 'PROTOBUF_DESCRIPTOR'
	| 'JSON_FRAGMENT'
	| 'RESHAPR_PROMPTS'
	| 'RESHAPR_CUSTOM_TOOLS'
	| 'RESHAPR_RESOURCES'
	| 'RESHAPR_TOOLS_OUTPUT_FILTERS'
	| 'RESHAPR_SKILLS';

export type ReshaprArtifactKind =
	| 'Prompts'
	| 'CustomTools'
	| 'Resources'
	| 'ToolsOutputFilters'
	| 'Skills';

export type EditorMode = 'create' | 'edit' | 'view';

export type ArtifactRef = {
	id: string;
	organizationId: string | null;
	name: string;
	path: string | null;
	mainArtifact: boolean;
	sourceArtifact: string | null;
	type: ArtifactType;
	capabilities: string[];
};

export type ArtifactDetail = ArtifactRef & {
	content: string | null;
};

/** List filter on the Artifacts hub. */
export type ArtifactTypeFilter = 'all' | 'main' | ArtifactType;

export type ServiceRef = {
	name: string;
	version: string;
};
