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

import type {
	ArtifactDetail,
	ArtifactRef,
	ArtifactType,
	ReshaprArtifactKind
} from './types.js';

const ARTIFACT_TYPES = new Set<string>([
	'JSON_SCHEMA',
	'OPEN_API_SPEC',
	'GRAPHQL_SCHEMA',
	'PROTOBUF_SCHEMA',
	'PROTOBUF_DESCRIPTOR',
	'JSON_FRAGMENT',
	'RESHAPR_PROMPTS',
	'RESHAPR_CUSTOM_TOOLS',
	'RESHAPR_RESOURCES',
	'RESHAPR_TOOLS_OUTPUT_FILTERS',
	'RESHAPR_SKILLS'
]);

function parseArtifactType(value: unknown): ArtifactType {
	if (typeof value === 'string' && ARTIFACT_TYPES.has(value)) {
		return value as ArtifactType;
	}
	return 'JSON_FRAGMENT';
}

export function parseArtifactRef(raw: unknown): ArtifactRef | null {
	if (!raw || typeof raw !== 'object') return null;
	const o = raw as Record<string, unknown>;
	if (typeof o.id !== 'string') return null;
	return {
		id: o.id,
		organizationId: typeof o.organizationId === 'string' ? o.organizationId : null,
		name: typeof o.name === 'string' ? o.name : '—',
		path: typeof o.path === 'string' ? o.path : null,
		mainArtifact: o.mainArtifact === true,
		sourceArtifact: typeof o.sourceArtifact === 'string' ? o.sourceArtifact : null,
		type: parseArtifactType(o.type),
		capabilities: parseCapabilities(o.capabilities)
	};
}

function parseCapabilities(value: unknown): string[] {
	if (!Array.isArray(value)) return [];
	return value.filter((v): v is string => typeof v === 'string');
}

export function parseArtifactDetail(raw: unknown): ArtifactDetail | null {
	const ref = parseArtifactRef(raw);
	if (!ref) return null;
	if (!raw || typeof raw !== 'object') return null;
	const o = raw as Record<string, unknown>;
	return {
		...ref,
		content: typeof o.content === 'string' ? o.content : null
	};
}

export function parseArtifactRefList(raw: unknown): ArtifactRef[] {
	if (!Array.isArray(raw)) return [];
	return raw.map(parseArtifactRef).filter((row): row is ArtifactRef => row != null);
}

/** Read `kind:` from YAML or JSON artifact content (best-effort, client-side). */
export function extractKindFromYaml(content: string): ReshaprArtifactKind | null {
	const trimmed = content.trim();
	if (!trimmed) return null;

	if (trimmed.startsWith('{')) {
		try {
			const doc = JSON.parse(trimmed) as Record<string, unknown>;
			return normalizeKind(doc.kind);
		} catch {
			return null;
		}
	}

	const match = /^kind:\s*(\S+)/m.exec(content);
	return match ? normalizeKind(match[1]) : null;
}

function normalizeKind(value: unknown): ReshaprArtifactKind | null {
	if (value === 'Prompts' || value === 'CustomTools' || value === 'Resources') {
		return value;
	}
	if (value === 'ToolsOutputFilters') return 'ToolsOutputFilters';
	if (value === 'Skills') return 'Skills';
	return null;
}
