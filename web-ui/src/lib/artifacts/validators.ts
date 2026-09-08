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

import { isMap, isScalar, isSeq, parseDocument } from 'yaml';
import type { Document, Node, Scalar, YAMLMap } from 'yaml';
import type { ServiceApi } from '$lib/serviceHub.js';
import type { ReshaprArtifactKind, ServiceRef } from './types.js';

/**
 * An editor-side validation finding surfaced as a Monaco marker. Positions are 1-based (Monaco
 * convention) so a finding maps directly onto a Monaco marker. Most findings are non-blocking
 * warnings (yellow); a finding with severity 'error' is blocking (red) and gates saving, matching a
 * validation the control plane also enforces at import time.
 */
export type ValidatorWarning = {
	/** Stable id of the validator that produced the warning (used for grouping/debugging). */
	validatorId: string;
	message: string;
	/** 'error' is blocking (gates saving); 'warning' (default) is informational only. */
	severity: 'error' | 'warning';
	startLineNumber: number;
	startColumn: number;
	endLineNumber: number;
	endColumn: number;
};

/**
 * A cross-reference index resolved once from the control plane. It answers "does this tool /
 * service exist in reShapr?" for reference-existence validators.
 */
export type ReshaprReferenceIndex = {
	/** Tool names available on the current service (backend operations + custom-tool capabilities). */
	knownTools: Set<string>;
	/** Known services expressed as `${name}:${version}` for cross-service references. */
	knownServices: Set<string>;
};

/** The public context the editor provides when running validators for a document. */
export type ValidatorContext = {
	content: string;
	kind: ReshaprArtifactKind;
	service: ServiceRef;
	/** Control-plane id of the edited service (absent while creating outside a service scope). */
	serviceId?: string;
	api: ServiceApi;
	/** Lazily resolve (and cache) the cross-reference index. */
	loadReferenceIndex: () => Promise<ReshaprReferenceIndex>;
};

/** Position helpers injected into each validator so it can map YAML nodes to marker positions. */
type ValidatorHelpers = {
	positionAt: (offset: number) => { lineNumber: number; column: number };
	warnNode: (
		validatorId: string,
		node: Node | null | undefined,
		message: string,
		severity?: 'error' | 'warning'
	) => ValidatorWarning;
	warnRange: (
		validatorId: string,
		start: number,
		end: number,
		message: string,
		severity?: 'error' | 'warning'
	) => ValidatorWarning;
};

/** The full context passed to a validator implementation. */
export type ValidatorRunContext = ValidatorContext & ValidatorHelpers;

export type Validator = {
	id: string;
	validate: (
		doc: Document.Parsed,
		ctx: ValidatorRunContext
	) => ValidatorWarning[] | Promise<ValidatorWarning[]>;
};

/* -------------------------------------------------------------------------------------------------
 * Position utilities
 * ---------------------------------------------------------------------------------------------- */

function buildLineStarts(content: string): number[] {
	const starts = [0];
	for (let i = 0; i < content.length; i++) {
		if (content.charCodeAt(i) === 10 /* \n */) starts.push(i + 1);
	}
	return starts;
}

function makePositionAt(lineStarts: number[], length: number) {
	return (offset: number): { lineNumber: number; column: number } => {
		const clamped = Math.max(0, Math.min(offset, length));
		let lo = 0;
		let hi = lineStarts.length - 1;
		while (lo < hi) {
			const mid = (lo + hi + 1) >> 1;
			if (lineStarts[mid] <= clamped) lo = mid;
			else hi = mid - 1;
		}
		return { lineNumber: lo + 1, column: clamped - lineStarts[lo] + 1 };
	};
}

/** The `[start, valueEnd]` source offsets of a YAML node, or `null` when unavailable. */
function nodeOffsets(node: Node | null | undefined): [number, number] | null {
	const range = (node as { range?: [number, number, number] } | null | undefined)?.range;
	if (!range || typeof range[0] !== 'number') return null;
	const start = range[0];
	const end = typeof range[1] === 'number' && range[1] > start ? range[1] : start + 1;
	return [start, end];
}

/* -------------------------------------------------------------------------------------------------
 * YAML navigation helpers
 * ---------------------------------------------------------------------------------------------- */

function asMapNode(node: unknown): YAMLMap | null {
	return isMap(node) ? (node as YAMLMap) : null;
}

/** The string key of a map pair, or `null` when it is not a plain scalar. */
function pairKey(pair: { key?: unknown }): string | null {
	const key = pair.key;
	if (isScalar(key) && typeof (key as Scalar).value === 'string') {
		return (key as Scalar).value as string;
	}
	return null;
}

/** Iterate the (key, valueNode) entries of a mapping node. */
function mapEntries(map: YAMLMap): Array<{ key: string | null; keyNode: unknown; value: unknown }> {
	return map.items.map((pair) => ({
		key: pairKey(pair),
		keyNode: pair.key,
		value: pair.value
	}));
}

/** Collect the property paths declared under an `input` JSON-schema node (dot-joined, recursive). */
function collectInputVarPaths(inputNode: unknown): Set<string> {
	const paths = new Set<string>();
	const inputMap = asMapNode(inputNode);
	if (!inputMap) return paths;
	const properties = asMapNode(inputMap.get('properties', true));
	if (!properties) return paths;
	const walk = (props: YAMLMap, prefix: string) => {
		for (const { key, value } of mapEntries(props)) {
			if (!key) continue;
			const path = prefix ? `${prefix}.${key}` : key;
			paths.add(path);
			const schema = asMapNode(value);
			const nested = schema ? asMapNode(schema.get('properties', true)) : null;
			if (nested) walk(nested, path);
		}
	};
	walk(properties, '');
	return paths;
}

/** Find `${var}` occurrences inside a scalar, returning the variable and absolute source offsets. */
function findPlaceholders(
	scalar: Scalar,
	content: string
): Array<{ name: string; start: number; end: number }> {
	const offsets = nodeOffsets(scalar);
	if (!offsets) return [];
	const [from, to] = offsets;
	const slice = content.slice(from, Math.max(to, from));
	const out: Array<{ name: string; start: number; end: number }> = [];
	const re = /\$\{([^}]+)\}/g;
	let match: RegExpExecArray | null;
	while ((match = re.exec(slice)) !== null) {
		out.push({
			name: match[1].trim(),
			start: from + match.index,
			end: from + match.index + match[0].length
		});
	}
	return out;
}

/** Visit every scalar leaf under a node (used to walk `arguments` templates). */
function visitScalars(node: unknown, visit: (scalar: Scalar) => void): void {
	if (isScalar(node)) {
		visit(node as Scalar);
	} else if (isMap(node)) {
		for (const pair of (node as YAMLMap).items) visitScalars(pair.value, visit);
	} else if (isSeq(node)) {
		for (const item of (node as { items: unknown[] }).items) visitScalars(item, visit);
	}
}

/* -------------------------------------------------------------------------------------------------
 * Reference index
 * ---------------------------------------------------------------------------------------------- */

/**
 * Resolve the set of known tools and services from the control plane. Best-effort: any failed
 * request degrades gracefully to an empty set so validation never blocks on transient errors.
 */
export async function buildReferenceIndex(
	api: ServiceApi,
	serviceId?: string
): Promise<ReshaprReferenceIndex> {
	const knownTools = new Set<string>();
	const knownServices = new Set<string>();

	try {
		const services = await api.listServices();
		if (Array.isArray(services)) {
			for (const raw of services) {
				const svc = raw as { name?: unknown; version?: unknown } | null;
				if (svc && typeof svc.name === 'string' && typeof svc.version === 'string') {
					knownServices.add(`${svc.name}:${svc.version}`);
				}
			}
		}
	} catch {
		/* best-effort */
	}

	if (serviceId) {
		try {
			const svc = (await api.getService(serviceId)) as { operations?: unknown } | null;
			const ops = svc && Array.isArray(svc.operations) ? svc.operations : [];
			for (const op of ops) {
				const name = (op as { name?: unknown } | null)?.name;
				if (typeof name === 'string') knownTools.add(name);
			}
		} catch {
			/* best-effort */
		}
		try {
			const refs = await api.listArtifactRefsByService(serviceId);
			if (Array.isArray(refs)) {
				for (const ref of refs) {
					const caps = (ref as { capabilities?: unknown } | null)?.capabilities;
					if (Array.isArray(caps)) {
						for (const cap of caps) if (typeof cap === 'string') knownTools.add(cap);
					}
				}
			}
		} catch {
			/* best-effort */
		}
	}

	return { knownTools, knownServices };
}

/* -------------------------------------------------------------------------------------------------
 * Script compilation (CustomTools scripted form)
 * ---------------------------------------------------------------------------------------------- */

/** The methods exposed by the injected `rs` façade (see docs/custom-tools-scripting-rs-facade.md). */
const RS_FACADE_METHODS = new Set(['callTool', 'callToolAsync', 'awaitPromises', 'fail']);

/** Cached probe: whether importing a blob ES module is available in this environment. */
let blobModuleImportSupported: boolean | null = null;

async function importBlobModule(source: string): Promise<void> {
	const url = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
	try {
		await import(/* @vite-ignore */ url);
	} finally {
		URL.revokeObjectURL(url);
	}
}

async function ensureBlobModuleImportSupported(): Promise<boolean> {
	if (blobModuleImportSupported !== null) return blobModuleImportSupported;
	if (typeof window === 'undefined' || typeof URL.createObjectURL !== 'function') {
		blobModuleImportSupported = false;
		return false;
	}
	try {
		await importBlobModule('export default 1;');
		blobModuleImportSupported = true;
	} catch {
		blobModuleImportSupported = false;
	}
	return blobModuleImportSupported;
}

/**
 * Compile a custom-tool script as an in-memory ES module that imports the `rs` façade from a blob
 * URL, mirroring the proxy wrapping (`function __process() { <script> }`, `input` in scope). The
 * user body is never executed — only parsed/compiled — so validation stays side-effect free.
 *
 * @returns `null` when the script compiles, otherwise the compiler error message.
 */
async function compileScript(script: string): Promise<string | null> {
	const rsStub =
		'export const rs = {' +
		[...RS_FACADE_METHODS].map((m) => `${m}() {}`).join(', ') +
		'};\nexport default rs;';
	const rsUrl = URL.createObjectURL(new Blob([rsStub], { type: 'text/javascript' }));
	const moduleSource =
		`import { rs } from ${JSON.stringify(rsUrl)};\n` +
		'const input = {};\n' +
		'export function __process() {\n' +
		script +
		'\n}\n' +
		'export const __refs = [rs, input];\n';
	try {
		await importBlobModule(moduleSource);
		return null;
	} catch (error) {
		return error instanceof Error ? error.message : String(error);
	} finally {
		URL.revokeObjectURL(rsUrl);
	}
}

/* -------------------------------------------------------------------------------------------------
 * Validators
 * ---------------------------------------------------------------------------------------------- */

/** CustomTools: referenced `tool`/`service` names must exist in reShapr. */
const customToolsReferencesValidator: Validator = {
	id: 'custom-tools-references',
	async validate(doc, ctx) {
		const customTools = asMapNode(doc.get('customTools', true));
		if (!customTools) return [];

		const index = await ctx.loadReferenceIndex();
		const localTools = new Set(
			mapEntries(customTools)
				.map((entry) => entry.key)
				.filter((key): key is string => key != null)
		);
		const isKnownTool = (name: string) => index.knownTools.has(name) || localTools.has(name);
		const warnings: ValidatorWarning[] = [];

		for (const { value } of mapEntries(customTools)) {
			const tool = asMapNode(value);
			if (!tool) continue;

			// Declarative form: a single target `tool`.
			const declared = tool.get('tool', true);
			if (isScalar(declared) && typeof declared.value === 'string' && !isKnownTool(declared.value)) {
				warnings.push(
					ctx.warnNode(
						this.id,
						declared as Node,
						`Tool "${declared.value}" is not defined in service "${ctx.service.name}".`
					)
				);
			}

			// Scripted form: an allow-list of `tools` (optionally cross-service).
			const tools = tool.get('tools', true);
			if (isSeq(tools)) {
				for (const item of (tools as { items: unknown[] }).items) {
					const entry = asMapNode(item);
					if (!entry) continue;
					const svc = entry.get('service', true);
					const toolName = entry.get('tool', true);
					if (isScalar(svc) && typeof svc.value === 'string') {
						// Cross-service reference: validate the service exists (tool is resolved remotely).
						if (!index.knownServices.has(svc.value)) {
							warnings.push(
								ctx.warnNode(
									this.id,
									svc as Node,
									`Service "${svc.value}" is not registered in reShapr.`
								)
							);
						}
					} else if (isScalar(toolName) && typeof toolName.value === 'string') {
						if (!isKnownTool(toolName.value)) {
							warnings.push(
								ctx.warnNode(
									this.id,
									toolName as Node,
									`Tool "${toolName.value}" is not defined in service "${ctx.service.name}".`
								)
							);
						}
					}
				}
			}
		}

		return warnings;
	}
};

/** CustomTools: `${var}` placeholders in `arguments` must be declared in `input.properties`. */
const customToolsPlaceholdersValidator: Validator = {
	id: 'custom-tools-placeholders',
	validate(doc, ctx) {
		const customTools = asMapNode(doc.get('customTools', true));
		if (!customTools) return [];

		const warnings: ValidatorWarning[] = [];
		for (const { value } of mapEntries(customTools)) {
			const tool = asMapNode(value);
			if (!tool) continue;
			const args = tool.get('arguments', true);
			if (args == null) continue;
			const declared = collectInputVarPaths(tool.get('input', true));

			visitScalars(args, (scalar) => {
				if (typeof scalar.value !== 'string') return;
				// The proxy only substitutes when the whole value is exactly `${var}`.
				const match = /^\s*\$\{([^}]+)\}\s*$/.exec(scalar.value);
				if (!match) return;
				const name = match[1].trim();
				if (declared.has(name)) return;
				const placeholder = findPlaceholders(scalar, ctx.content)[0];
				const message = `Placeholder "\${${name}}" is not declared in this tool's input.`;
				// Blocking: the control plane rejects this at import time, so gate saving here too.
				warnings.push(
					placeholder
						? ctx.warnRange(this.id, placeholder.start, placeholder.end, message, 'error')
						: ctx.warnNode(this.id, scalar as Node, message, 'error')
				);
			});
		}
		return warnings;
	}
};

/** CustomTools: scripted-form `script` must compile as valid JS using the `rs` façade correctly. */
const customToolsScriptValidator: Validator = {
	id: 'custom-tools-script',
	async validate(doc, ctx) {
		const customTools = asMapNode(doc.get('customTools', true));
		if (!customTools) return [];

		const warnings: ValidatorWarning[] = [];
		const canCompile = await ensureBlobModuleImportSupported();

		for (const { value } of mapEntries(customTools)) {
			const tool = asMapNode(value);
			if (!tool) continue;
			const scriptNode = tool.get('script', true);
			if (!isScalar(scriptNode) || typeof scriptNode.value !== 'string') continue;
			const script = scriptNode.value;

			// Static check: flag usages of unknown `rs.<method>(...)` façade methods.
			const offsets = nodeOffsets(scriptNode as Node);
			if (offsets) {
				const [from, to] = offsets;
				// Scan the raw source slice (not the decoded value) so offsets map back to positions.
				const source = ctx.content.slice(from, Math.max(to, from));
				const methodRe = /\brs\.([A-Za-z_$][\w$]*)\s*\(/g;
				let match: RegExpExecArray | null;
				while ((match = methodRe.exec(source)) !== null) {
					const method = match[1];
					if (RS_FACADE_METHODS.has(method)) continue;
					const start = from + match.index + match[0].indexOf(method);
					warnings.push(
						ctx.warnRange(
							this.id,
							start,
							start + method.length,
							`"rs.${method}" is not part of the reShapr script API ` +
								`(available: ${[...RS_FACADE_METHODS].join(', ')}).`
						)
					);
				}
			}

			// Compile check: parse the script as an ES module importing the `rs` façade.
			if (canCompile) {
				const error = await compileScript(script);
				if (error) {
					warnings.push(
						ctx.warnNode(this.id, scriptNode as Node, `Script does not compile: ${error}`)
					);
				}
			}
		}

		return warnings;
	}
};

/* -------------------------------------------------------------------------------------------------
 * Script call allow-list (CustomTools scripted form)
 * ---------------------------------------------------------------------------------------------- */

/** Read a JS string literal starting at `index` (a quote char). Null for dynamic/interpolated. */
function readStringLiteral(src: string, index: number): { value: string; end: number } | null {
	const quote = src[index];
	if (quote !== '"' && quote !== "'" && quote !== '`') return null;
	let value = '';
	for (let j = index + 1; j < src.length; j++) {
		const ch = src[j];
		if (ch === '\\') {
			value += src[j + 1] ?? '';
			j++;
			continue;
		}
		if (ch === quote) return { value, end: j + 1 };
		// A template literal carrying an interpolation is not a static reference.
		if (quote === '`' && ch === '$' && src[j + 1] === '{') return null;
		value += ch;
	}
	return null;
}

/** Skip whitespace from `index`, returning the next non-space offset. */
function skipWhitespace(src: string, index: number): number {
	let i = index;
	while (i < src.length && /\s/.test(src[i])) i++;
	return i;
}

/**
 * Mirror of the proxy allow-list matching (proxy .../mcp/DeclaredTool.java#matches): tool names
 * must be equal and both sides must be same-service, or both cross-service with equal coordinate.
 */
function declaredToolMatches(
	declaredService: string | null,
	declaredTool: string,
	callService: string | null,
	callTool: string
): boolean {
	if (declaredTool !== callTool) return false;
	const declaredSame = declaredService == null || declaredService.trim() === '';
	const callSame = callService == null || callService.trim() === '';
	if (declaredSame || callSame) return declaredSame && callSame;
	return declaredService === callService;
}

type ScriptCall = { service: string | null; tool: string; start: number; end: number };

/**
 * Extract the statically-resolvable `rs.callTool` / `rs.callToolAsync` invocations from a script
 * source slice. Following the `rs` façade arity rules: `(tool, params)` is a same-service call,
 * `(service, tool, params)` a cross-service one. Calls whose leading argument is not a string
 * literal are dynamic and skipped (never flagged).
 */
function extractScriptCalls(source: string): ScriptCall[] {
	const calls: ScriptCall[] = [];
	const re = /\brs\.(?:callTool|callToolAsync)\s*\(/g;
	let match: RegExpExecArray | null;
	while ((match = re.exec(source)) !== null) {
		const argStart = skipWhitespace(source, match.index + match[0].length);
		const arg0 = readStringLiteral(source, argStart);
		if (!arg0) continue;
		let arg1: { value: string; end: number } | null = null;
		const afterArg0 = skipWhitespace(source, arg0.end);
		if (source[afterArg0] === ',') {
			arg1 = readStringLiteral(source, skipWhitespace(source, afterArg0 + 1));
		}
		if (arg1) {
			// (service, tool, params) — cross-service form.
			calls.push({ service: arg0.value, tool: arg1.value, start: argStart, end: arg1.end });
		} else {
			// (tool, params) — same-service form.
			calls.push({ service: null, tool: arg0.value, start: argStart, end: arg0.end });
		}
	}
	return calls;
}

/**
 * CustomTools: every `rs.callTool` / `rs.callToolAsync` target in a script must be declared in the
 * same custom tool's `tools` allow-list (the security allow-list enforced by the proxy at runtime).
 */
const customToolsScriptAllowlistValidator: Validator = {
	id: 'custom-tools-script-allowlist',
	validate(doc, ctx) {
		const customTools = asMapNode(doc.get('customTools', true));
		if (!customTools) return [];

		const warnings: ValidatorWarning[] = [];
		for (const { value } of mapEntries(customTools)) {
			const tool = asMapNode(value);
			if (!tool) continue;
			const scriptNode = tool.get('script', true);
			if (!isScalar(scriptNode) || typeof scriptNode.value !== 'string') continue;
			const offsets = nodeOffsets(scriptNode as Node);
			if (!offsets) continue;

			// The allow-list declared by this custom tool.
			const declared: Array<{ service: string | null; tool: string }> = [];
			const toolsSeq = tool.get('tools', true);
			if (isSeq(toolsSeq)) {
				for (const item of (toolsSeq as { items: unknown[] }).items) {
					const entry = asMapNode(item);
					if (!entry) continue;
					const toolName = entry.get('tool', true);
					if (!isScalar(toolName) || typeof toolName.value !== 'string') continue;
					const svc = entry.get('service', true);
					const service = isScalar(svc) && typeof svc.value === 'string' ? svc.value : null;
					declared.push({ service, tool: toolName.value });
				}
			}

			const [from, to] = offsets;
			const source = ctx.content.slice(from, Math.max(to, from));
			for (const call of extractScriptCalls(source)) {
				const allowed = declared.some((entry) =>
					declaredToolMatches(entry.service, entry.tool, call.service, call.tool)
				);
				if (allowed) continue;
				const target =
					call.service == null
						? `Tool "${call.tool}"`
						: `Tool "${call.tool}" on service "${call.service}"`;
				warnings.push(
					ctx.warnRange(
						this.id,
						from + call.start,
						from + call.end,
						`${target} called by the script is not declared in this custom tool's 'tools' allow-list.`
					)
				);
			}
		}
		return warnings;
	}
};

/** ToolsOutputFilters: each filtered key must reference a tool that exists in reShapr. */
const toolsOutputFiltersReferencesValidator: Validator = {
	id: 'tools-output-filters-references',
	async validate(doc, ctx) {
		const filters = asMapNode(doc.get('filters', true));
		if (!filters) return [];

		const index = await ctx.loadReferenceIndex();
		const warnings: ValidatorWarning[] = [];
		for (const pair of filters.items) {
			const key = pairKey(pair);
			if (!key) continue;
			if (!index.knownTools.has(key)) {
				warnings.push(
					ctx.warnNode(
						this.id,
						pair.key as Node,
						`Tool "${key}" is not defined in service "${ctx.service.name}".`
					)
				);
			}
		}
		return warnings;
	}
};

/** Prompts: `${var}` placeholders in `result` must be declared in `arguments`. */
const promptsPlaceholdersValidator: Validator = {
	id: 'prompts-placeholders',
	validate(doc, ctx) {
		const prompts = asMapNode(doc.get('prompts', true));
		if (!prompts) return [];

		const warnings: ValidatorWarning[] = [];
		for (const { value } of mapEntries(prompts)) {
			const prompt = asMapNode(value);
			if (!prompt) continue;

			const argNames = new Set<string>();
			const argsNode = prompt.get('arguments', true);
			if (isSeq(argsNode)) {
				for (const item of (argsNode as { items: unknown[] }).items) {
					const arg = asMapNode(item);
					const name = arg?.get('name', true);
					if (isScalar(name) && typeof name.value === 'string') argNames.add(name.value);
				}
			}

			const result = prompt.get('result', true);
			if (!isScalar(result) || typeof result.value !== 'string') continue;
			for (const placeholder of findPlaceholders(result as Scalar, ctx.content)) {
				if (argNames.has(placeholder.name)) continue;
				warnings.push(
					ctx.warnRange(
						this.id,
						placeholder.start,
						placeholder.end,
						`Placeholder "\${${placeholder.name}}" is not declared in this prompt's arguments.`
					)
				);
			}
		}
		return warnings;
	}
};

/** Per-kind validator registry. Kinds without an entry simply run no custom validation. */
export const VALIDATORS_BY_KIND: Record<ReshaprArtifactKind, Validator[]> = {
	Prompts: [promptsPlaceholdersValidator],
	CustomTools: [
		customToolsReferencesValidator,
		customToolsPlaceholdersValidator,
		customToolsScriptValidator,
		customToolsScriptAllowlistValidator
	],
	Resources: [],
	ToolsOutputFilters: [toolsOutputFiltersReferencesValidator]
};

/** Whether a given kind has any custom validators wired up. */
export function hasValidators(kind: ReshaprArtifactKind): boolean {
	return (VALIDATORS_BY_KIND[kind] ?? []).length > 0;
}

/**
 * Run every validator registered for the document's kind and return the aggregated warnings.
 *
 * Validators are best-effort and non-blocking: parsing/validation failures are swallowed so a
 * malformed document (already flagged by the schema) never throws here.
 */
export async function runValidators(ctx: ValidatorContext): Promise<ValidatorWarning[]> {
	const validators = VALIDATORS_BY_KIND[ctx.kind] ?? [];
	if (validators.length === 0 || ctx.content.trim().length === 0) return [];

	let doc: Document.Parsed;
	try {
		doc = parseDocument(ctx.content);
	} catch {
		return [];
	}
	if (!doc || doc.contents == null || !isMap(doc.contents)) return [];

	const lineStarts = buildLineStarts(ctx.content);
	const positionAt = makePositionAt(lineStarts, ctx.content.length);
	const helpers: ValidatorHelpers = {
		positionAt,
		warnRange(validatorId, start, end, message, severity = 'warning') {
			const startPos = positionAt(start);
			const endPos = positionAt(end);
			return {
				validatorId,
				message,
				severity,
				startLineNumber: startPos.lineNumber,
				startColumn: startPos.column,
				endLineNumber: endPos.lineNumber,
				endColumn: endPos.column
			};
		},
		warnNode(validatorId, node, message, severity = 'warning') {
			const offsets = nodeOffsets(node) ?? [0, 1];
			return helpers.warnRange(validatorId, offsets[0], offsets[1], message, severity);
		}
	};

	const runCtx: ValidatorRunContext = { ...ctx, ...helpers };
	const results = await Promise.all(
		validators.map(async (validator) => {
			try {
				return await validator.validate(doc, runCtx);
			} catch {
				return [];
			}
		})
	);
	return results.flat();
}
