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

/**
 * Shared, framework-agnostic helpers to present a configuration plan the same
 * way across the app (exposition detail page, create-exposition wizard, …).
 */
import {
	CAPABILITY_ICONS,
	CAPABILITY_STYLES,
	CUSTOM_ARTIFACT_TYPES,
	type ArtifactRef,
	type ArtifactType
} from '#lib/artifacts/index.js';

export { CAPABILITY_ICONS, CAPABILITY_STYLES };

export function recOf(v: unknown): Record<string, unknown> | null {
	return v && typeof v === 'object' ? (v as Record<string, unknown>) : null;
}
export function strOf(v: unknown): string | null {
	return typeof v === 'string' && v.trim() !== '' ? v.trim() : null;
}
export function arrOf(v: unknown): unknown[] {
	return Array.isArray(v) ? v : [];
}

// ── Operations ──────────────────────────────────────────────────────────────
export const HTTP_VERBS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);

export const METHOD_STYLES: Record<string, string> = {
	GET: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
	POST: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
	PUT: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400',
	PATCH: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
	DELETE: 'bg-rose-500/10 text-rose-600 ring-rose-500/20 dark:text-rose-400'
};

export function methodStyle(label: string | null): string {
	const key = (label ?? '').toUpperCase();
	return METHOD_STYLES[key] ?? 'bg-muted text-muted-foreground ring-border';
}

export function splitOp(op: string): { method: string | null; rest: string } {
	const m = op.trim().match(/^(\S+)\s+(.+)$/);
	if (m && HTTP_VERBS.has(m[1].toUpperCase())) return { method: m[1].toUpperCase(), rest: m[2] };
	return { method: null, rest: op.trim() };
}

export type OpsMode = 'include' | 'exclude' | 'all';

export function planOpsMode(plan: Record<string, unknown> | null): OpsMode {
	const inc = arrOf(plan?.includedOperations);
	const exc = arrOf(plan?.excludedOperations);
	if (inc.length) return 'include';
	if (exc.length) return 'exclude';
	return 'all';
}

export function planOperations(plan: Record<string, unknown> | null): string[] {
	const mode = planOpsMode(plan);
	return (mode === 'exclude' ? arrOf(plan?.excludedOperations) : arrOf(plan?.includedOperations)).map(
		String
	);
}

// ── Capabilities (from custom artifacts included by the plan) ────────────────
export type CapabilityGroup = {
	type: ArtifactType;
	label: string;
	items: { name: string; artifactName: string }[];
};

export function capabilityGroups(
	plan: Record<string, unknown> | null,
	artifacts: ArtifactRef[]
): CapabilityGroup[] {
	const included = arrOf(plan?.includedArtifacts).map(String);
	const eligible = artifacts.filter(
		(a) => !a.mainArtifact && (included.length === 0 || included.includes(a.name))
	);
	return CUSTOM_ARTIFACT_TYPES.map(({ type, capabilityLabel: label }) => {
		const items = eligible
			.filter((a) => a.type === type)
			.flatMap((a) => a.capabilities.map((name) => ({ name, artifactName: a.name })));
		return { type, label, items };
	}).filter((g) => g.items.length > 0);
}

// ── MCP endpoint (client-facing) authentication ─────────────────────────────
export type McpAuth =
	| { kind: 'oauth'; servers: string[]; scopes: string[]; jwksUri: string | null; disableAudienceValidation: boolean; staticAudiences: string[] }
	| { kind: 'apikey' }
	| { kind: 'none' };

export function mcpAuthOf(plan: Record<string, unknown> | null): McpAuth {
	const oauth = recOf(plan?.oauth2Configuration);
	if (oauth) {
		return {
			kind: 'oauth',
			servers: arrOf(oauth.authorizationServers).map(String),
			scopes: arrOf(oauth.scopes).map(String),
			jwksUri: strOf(oauth.jwksUri),
			disableAudienceValidation: !!oauth.disableAudienceValidation,
			staticAudiences: arrOf(oauth.staticAudiences).map(String)
		};
	}
	if (strOf(plan?.apiKey)) return { kind: 'apikey' };
	return { kind: 'none' };
}

// ── Backend ─────────────────────────────────────────────────────────────────
export function backendEndpointOf(plan: Record<string, unknown> | null): string | null {
	return strOf(plan?.backendEndpoint);
}

export function backendTimeoutOf(plan: Record<string, unknown> | null): string | null {
	const t = plan?.backendTimeout;
	if (t == null || t === '') return null;
	const n = Number(t);
	return Number.isNaN(n) ? String(t as string | number) : `${n} ms`;
}

export function auditOf(plan: Record<string, unknown> | null): boolean {
	return plan?.audit === true;
}

/** Backend secret reference resolved from a secret id. */
export type BackendSecret = { name: string; type: string };

