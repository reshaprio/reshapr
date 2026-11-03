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

import { EDITABLE_KINDS } from '#lib/artifacts/kinds.js';
import type { SchemasSettings } from 'monaco-yaml';

import customToolsSchema from '#lib/schemas/CustomTools-v1alpha1-schema.json';
import promptsSchema from '#lib/schemas/Prompts-v1alpha1-schema.json';
import resourcesSchema from '#lib/schemas/Resources-v1alpha1-schema.json';
import toolsOutputFiltersSchema from '#lib/schemas/ToolsOutputFilters-v1alpha1-schema.json';

type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

const SCHEMA_BY_PATH: Record<string, Record<string, unknown>> = {
	'/schemas/Prompts-v1alpha1-schema.json': promptsSchema,
	'/schemas/CustomTools-v1alpha1-schema.json': customToolsSchema,
	'/schemas/Resources-v1alpha1-schema.json': resourcesSchema,
	'/schemas/ToolsOutputFilters-v1alpha1-schema.json': toolsOutputFiltersSchema
};

/** Inline local `#/…` refs so monaco-yaml sees properties under patternProperties. */
export function resolveLocalRefs<T extends Record<string, unknown>>(schema: T): T {
	const root = schema as unknown as JsonValue;
	const resolving = new WeakSet<object>();

	function resolve(node: JsonValue): JsonValue {
		if (node === null || typeof node !== 'object') return node;

		if (Array.isArray(node)) {
			return node.map((item) => resolve(item));
		}

		const obj = node as Record<string, JsonValue>;
		if ('$ref' in obj && typeof obj.$ref === 'string' && obj.$ref.startsWith('#/')) {
			if (resolving.has(obj)) return obj;
			resolving.add(obj);

			const refPath = obj.$ref.slice(2).split('/');
			let target: JsonValue = root;
			for (const segment of refPath) {
				if (target === null || typeof target !== 'object' || Array.isArray(target)) {
					throw new Error(`Unable to resolve ${obj.$ref}`);
				}
				target = (target as Record<string, JsonValue>)[segment];
			}
			const resolved = resolve(target);
			resolving.delete(obj);
			return resolved;
		}

		const out: Record<string, JsonValue> = {};
		for (const [key, value] of Object.entries(obj)) {
			out[key] = resolve(value);
		}
		return out;
	}

	return resolve(root) as T;
}

/**
 * monaco-yaml / yaml-language-server complete nested keys from `properties` and
 * `additionalProperties`, not from `patternProperties`. Reshapr maps use
 * `patternProperties: { "^.": … }` for dynamic keys (e.g. prompt names).
 */
export function promotePatternProperties<T extends JsonValue>(node: T): T {
	if (node === null || typeof node !== 'object') return node;

	if (Array.isArray(node)) {
		return node.map((item) => promotePatternProperties(item)) as T;
	}

	const obj = node as Record<string, JsonValue>;
	const out: Record<string, JsonValue> = {};

	for (const [key, value] of Object.entries(obj)) {
		if (key === 'patternProperties') continue;
		out[key] = promotePatternProperties(value);
	}

	const patternProps = obj.patternProperties;
	if (patternProps && typeof patternProps === 'object' && !Array.isArray(patternProps)) {
		const patterns = Object.keys(patternProps);
		if (patterns.length === 1 && patterns[0] === '^.') {
			const valueSchema = patternProps['^.'];
			if (valueSchema && typeof valueSchema === 'object') {
				out.additionalProperties = promotePatternProperties(valueSchema);
			}
		}
	}

	return out as T;
}

/** Monaco-only schema shaping — does not change control-plane validation files. */
export function adaptSchemaForMonaco<T extends Record<string, unknown>>(schema: T): T {
	return promotePatternProperties(resolveLocalRefs(schema) as JsonValue) as T;
}

function schemaSlug(schemaPath: string): string {
	return schemaPath.replace(/^\/schemas\//, '').replace(/-schema\.json$/, '');
}

/** Glob matched against the Monaco model URI (see `artifactModelUri`). */
export function schemaFileMatch(schemaPath: string): string {
	return `/reshapr-artifact/${schemaSlug(schemaPath)}/**`;
}

/** Stable URI namespace so monaco-yaml can bind the right JSON Schema per kind. */
export function artifactModelUri(schemaPath: string): string {
	return `file:///reshapr-artifact/${schemaSlug(schemaPath)}/document.yaml`;
}

/** Single-schema config for one editor instance — avoids fileMatch ambiguity. */
export function buildMonacoYamlSchemaForPath(schemaPath: string): SchemasSettings {
	const raw = SCHEMA_BY_PATH[schemaPath];
	if (!raw) {
		throw new Error(`Missing bundled schema for ${schemaPath}`);
	}
	const schema = adaptSchemaForMonaco(raw);
	return {
		uri: typeof schema.$id === 'string' ? schema.$id : schemaPath,
		fileMatch: ['*'],
		schema
	};
}

/** Bundled JSON Schemas — no runtime fetch (works in dev, Docker, offline). */
export function buildMonacoYamlSchemas(): SchemasSettings[] {
	return EDITABLE_KINDS.map((def) => {
		const raw = SCHEMA_BY_PATH[def.schemaPath];
		if (!raw) {
			throw new Error(`Missing bundled schema for ${def.schemaPath}`);
		}
		const schema = adaptSchemaForMonaco(raw);
		return {
			uri: typeof schema.$id === 'string' ? schema.$id : def.schemaPath,
			fileMatch: [schemaFileMatch(def.schemaPath)],
			schema
		};
	});
}
