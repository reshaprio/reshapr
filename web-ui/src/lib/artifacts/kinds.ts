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

import {
	BubbleChatIcon,
	File01Icon,
	FilterIcon,
	SparklesIcon,
	Wrench01Icon
} from '@hugeicons/core-free-icons';
import type { ArtifactType, ArtifactTypeFilter, ReshaprArtifactKind } from './types.js';

export type KindDefinition = {
	kind: ReshaprArtifactKind;
	artifactType: ArtifactType;
	label: string;
	schemaPath: string;
	isEditable: true;
};

/** Registry aligned with `ReshaprArtifactBuilder` kind → schema mappings. */
export const EDITABLE_KINDS: KindDefinition[] = [
	{
		kind: 'Prompts',
		artifactType: 'RESHAPR_PROMPTS',
		label: 'Prompts',
		schemaPath: '/schemas/Prompts-v1alpha1-schema.json',
		isEditable: true
	},
	{
		kind: 'CustomTools',
		artifactType: 'RESHAPR_CUSTOM_TOOLS',
		label: 'Custom tools',
		schemaPath: '/schemas/CustomTools-v1alpha1-schema.json',
		isEditable: true
	},
	{
		kind: 'Resources',
		artifactType: 'RESHAPR_RESOURCES',
		label: 'Resources',
		schemaPath: '/schemas/Resources-v1alpha1-schema.json',
		isEditable: true
	},
	{
		kind: 'ToolsOutputFilters',
		artifactType: 'RESHAPR_TOOLS_OUTPUT_FILTERS',
		label: 'Tools output filters',
		schemaPath: '/schemas/ToolsOutputFilters-v1alpha1-schema.json',
		isEditable: true
	},
	{
		kind: 'Skills',
		artifactType: 'RESHAPR_SKILLS',
		label: 'Skills',
		schemaPath: '/schemas/Skills-v1alpha1-schema.json',
		isEditable: true
	}
];

export type CustomArtifactDisplay = {
	type: ArtifactType;
	/** Label used in artifact selectors. */
	label: string;
	/** Label used for the capabilities group of this artifact type. */
	capabilityLabel: string;
	icon: typeof Wrench01Icon;
	/** Color-coded pill classes. */
	style: string;
};

/** Single source of truth (display order, labels, icon, style) for custom artifact types. */
export const CUSTOM_ARTIFACT_TYPES = [
	{
		type: 'RESHAPR_CUSTOM_TOOLS',
		label: 'Custom tools',
		capabilityLabel: 'Tools',
		icon: Wrench01Icon,
		style: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400'
	},
	{
		type: 'RESHAPR_PROMPTS',
		label: 'Prompts',
		capabilityLabel: 'Prompts',
		icon: BubbleChatIcon,
		style: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400'
	},
	{
		type: 'RESHAPR_RESOURCES',
		label: 'Resources',
		capabilityLabel: 'Resources',
		icon: File01Icon,
		style: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400'
	},
	{
		type: 'RESHAPR_TOOLS_OUTPUT_FILTERS',
		label: 'Output filters',
		capabilityLabel: 'Output filters',
		icon: FilterIcon,
		style: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
	},
	{
		type: 'RESHAPR_SKILLS',
		label: 'Skills',
		capabilityLabel: 'Skills',
		icon: SparklesIcon,
		style: 'bg-rose-500/10 text-rose-600 ring-rose-500/20 dark:text-rose-400'
	}
] as const satisfies readonly CustomArtifactDisplay[];

export type CustomArtifactType = (typeof CUSTOM_ARTIFACT_TYPES)[number]['type'];

export const CAPABILITY_ICONS = Object.fromEntries(
	CUSTOM_ARTIFACT_TYPES.map((d) => [d.type, d.icon])
) as Partial<Record<ArtifactType, typeof Wrench01Icon>>;

export const CAPABILITY_STYLES = Object.fromEntries(
	CUSTOM_ARTIFACT_TYPES.map((d) => [d.type, d.style])
) as Partial<Record<ArtifactType, string>>;

const KIND_BY_TYPE = new Map(
	EDITABLE_KINDS.map((def) => [def.artifactType, def] as const)
);

export const ARTIFACT_TYPE_LABELS: Record<ArtifactType, string> = {
	JSON_SCHEMA: 'JSON Schema',
	OPEN_API_SPEC: 'OpenAPI spec',
	GRAPHQL_SCHEMA: 'GraphQL schema',
	PROTOBUF_SCHEMA: 'Protobuf schema',
	PROTOBUF_DESCRIPTOR: 'Protobuf descriptor',
	JSON_FRAGMENT: 'JSON fragment',
	RESHAPR_PROMPTS: 'Prompts',
	RESHAPR_CUSTOM_TOOLS: 'Custom tools',
	RESHAPR_RESOURCES: 'Resources',
	RESHAPR_TOOLS_OUTPUT_FILTERS: 'Tools output filters',
	RESHAPR_SKILLS: 'Skills'
};

export const TYPE_FILTER_OPTIONS: { value: ArtifactTypeFilter; label: string }[] = [
	{ value: 'all', label: 'All types' },
	{ value: 'main', label: 'Main specification' },
	...EDITABLE_KINDS.map((def) => ({
		value: def.artifactType as ArtifactTypeFilter,
		label: def.label
	})),
	{ value: 'OPEN_API_SPEC', label: 'OpenAPI spec' },
	{ value: 'GRAPHQL_SCHEMA', label: 'GraphQL schema' },
	{ value: 'PROTOBUF_SCHEMA', label: 'Protobuf schema' },
	{ value: 'PROTOBUF_DESCRIPTOR', label: 'Protobuf descriptor' },
	{ value: 'JSON_SCHEMA', label: 'JSON Schema' },
	{ value: 'JSON_FRAGMENT', label: 'JSON fragment' }
];

export function getKindDefinition(kind: ReshaprArtifactKind): KindDefinition | undefined {
	return EDITABLE_KINDS.find((def) => def.kind === kind);
}

/**
 * Build the default title proposed when creating a new custom artifact.
 * Uses the kind label as base name and appends ` (n)` when artifacts with the
 * same name already exist, picking the smallest free index (starting at 2).
 */
export function buildDefaultArtifactTitle(
	kind: ReshaprArtifactKind,
	existingNames: string[] = []
): string {
	const base = getKindDefinition(kind)?.label ?? kind;
	const taken = new Set(existingNames.map((name) => name.trim()).filter(Boolean));
	if (!taken.has(base)) return base;
	let index = 2;
	while (taken.has(`${base} (${index})`)) index++;
	return `${base} (${index})`;
}

export function getKindForArtifactType(type: ArtifactType): KindDefinition | undefined {
	return KIND_BY_TYPE.get(type);
}

export function isEditableArtifactType(type: ArtifactType): boolean {
	return KIND_BY_TYPE.has(type);
}

export function artifactTypeLabel(type: ArtifactType): string {
	return ARTIFACT_TYPE_LABELS[type] ?? type;
}

export function matchesTypeFilter(
	artifact: { type: ArtifactType; mainArtifact: boolean },
	filter: ArtifactTypeFilter
): boolean {
	if (filter === 'all') return true;
	if (filter === 'main') return artifact.mainArtifact;
	return artifact.type === filter;
}
