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

import type { ReshaprArtifactKind, ServiceRef } from './types.js';
import { buildTemplate } from './templates.js';

/**
 * A curated, ready-to-insert example for an advanced artifact kind. Each example contributes a
 * single entry under an artifact root map (e.g. `customTools:`, `resources:`,
 * `resourceTemplates:`, `filters:`), so several examples can be composed into one document from a
 * blank page.
 */
export type ArtifactExample = {
	/** Stable identifier, unique per kind. */
	id: string;
	/** Short human-readable name shown on the insert button. */
	label: string;
	/** One-line explanation of the use case, shown as a tooltip. */
	description: string;
	/** The entry key (map key) inserted under the root map — used for de-duplication. */
	entryKey: string;
	/** YAML for one root-map entry, indented two spaces (i.e. as a child of the root map). */
	entry: string;
	/**
	 * The payload root map the entry belongs to. Defaults to the kind's first root key. Set it
	 * explicitly for kinds that expose several root maps (e.g. Resources → `resourceTemplates`).
	 */
	rootKey?: string;
};

/** The payload root map keys of each artifact kind (first one is the default). */
const PAYLOAD_ROOT_KEYS_BY_KIND: Record<ReshaprArtifactKind, string[]> = {
	Prompts: ['prompts'],
	CustomTools: ['customTools'],
	Resources: ['resources', 'resourceTemplates'],
	ToolsOutputFilters: ['filters'],
	Skills: ['skills']
};

function defaultRootKey(kind: ReshaprArtifactKind): string {
	return PAYLOAD_ROOT_KEYS_BY_KIND[kind][0];
}

const CUSTOM_TOOLS_EXAMPLES: ArtifactExample[] = [
	{
		id: 'operation-alias',
		label: 'Operation alias',
		description:
			'Expose a single backend operation under a friendlier name, pre-filling and templating its arguments (declarative form).',
		entryKey: 'get_user_profile',
		entry: `  # Operation alias — map the custom tool to one backend operation and template
  # its arguments with the \${input} notation so the LLM only sees what matters.
  get_user_profile:
    tool: user
    description: Fetch a user profile together with their latest followers.
    input:
      type: object
      properties:
        login:
          type: string
          description: The login of the user to fetch.
      required:
        - login
    arguments:
      login: \${login}
      __relation_followers:
        last: 10
`
	},
	{
		id: 'multi-operation-orchestration',
		label: 'Multi-operation orchestration',
		description:
			'Call several operations in sequence with rs.callTool and reshape their combined results into a single slim payload (scripted form).',
		entryKey: 'user_dashboard',
		entry: `  # Multi-operation orchestration — call several operations in sequence and
  # combine their results into one compact, LLM-friendly payload.
  user_dashboard:
    description: Build a compact dashboard from a user's profile and their repositories.
    input:
      type: object
      properties:
        login:
          type: string
          description: The login of the user to inspect.
      required:
        - login
    tools:
      - tool: user
      - tool: repositories
    script: |
      const profile = rs.callTool('user', { login: input.login });
      if (!profile.ok) {
        return { error: profile.error };
      }
      const repos = rs.callTool('repositories', { owner: input.login, first: 5 });
      return {
        login: input.login,
        name: profile.content.name,
        company: profile.content.company,
        repositories: repos.ok ? repos.content.nodes : []
      };
`
	},
	{
		id: 'async-multi-operation-orchestration',
		label: 'Async orchestration',
		description:
			'Start several calls concurrently with rs.callToolAsync then gather them with rs.awaitPromises to cut latency (scripted form).',
		entryKey: 'compare_users',
		entry: `  # Asynchronous multi-operation orchestration — start calls concurrently with
  # callToolAsync, then gather them with awaitPromises for lower latency.
  compare_users:
    description: Fetch two users in parallel and return a side-by-side comparison.
    input:
      type: object
      properties:
        firstUser:
          type: string
          description: The login of the first user to compare.
        secondUser:
          type: string
          description: The login of the second user to compare.
      required:
        - firstUser
        - secondUser
    tools:
      - tool: user
    script: |
      function summarize(result, login) {
        if (!result.ok) { return { login: login, error: result.error }; }
        return {
          login: result.content.login || login,
          name: result.content.name,
          company: result.content.company
        };
      }

      // Kick off both fetches concurrently, then await both.
      const p1 = rs.callToolAsync('user', { login: input.firstUser });
      const p2 = rs.callToolAsync('user', { login: input.secondUser });
      const results = rs.awaitPromises([p1, p2]);

      return {
        users: [
          summarize(results[0], input.firstUser),
          summarize(results[1], input.secondUser)
        ]
      };
`
	},
	{
		id: 'orchestration-error-handling',
		label: 'Exception handling',
		description:
			'Inspect each call\u2019s ok flag and surface a structured MCP error with rs.fail instead of leaking partial data (scripted form).',
		entryKey: 'safe_user_lookup',
		entry: `  # Exception handling during orchestration — check each call's ok flag and raise a
  # structured MCP error with rs.fail(message, data) instead of leaking partial data.
  safe_user_lookup:
    description: >-
      Look up a user and their issues, failing with a structured error when the
      user cannot be fetched.
    input:
      type: object
      properties:
        login:
          type: string
          description: The login of the user to look up.
      required:
        - login
    tools:
      - tool: user
      - service: "Issues API:1.0.0"
        tool: listIssues
    script: |
      const user = rs.callTool('user', { login: input.login });
      if (!user.ok) {
        // Turn an internal call failure into a structured MCP tool error.
        rs.fail('Could not fetch user', { login: input.login, cause: user.error });
      }

      // A cross-service call whose failure is recovered instead of propagated.
      const issues = rs.callTool('Issues API:1.0.0', 'listIssues', { author: input.login });
      return {
        login: user.content.login,
        issues: issues.ok ? issues.content : [],
        issuesError: issues.ok ? null : issues.error
      };
`
	}
];

const PROMPTS_EXAMPLES: ArtifactExample[] = [
	{
		id: 'simple-prompt',
		label: 'Simple prompt',
		description: 'A reusable, static instruction with no arguments — the client sends it as-is.',
		entryKey: 'list_products',
		entry: `  # Simple prompt — a reusable, static instruction with no arguments.
  list_products:
    title: List the products
    description: Browse the catalog to get all available products.
    result: List all the products available in the catalog.
`
	},
	{
		id: 'parameterized-prompt',
		label: 'Parameterized prompt',
		description:
			'Declare arguments and inject them into the result with the ${argument} notation; the client fills them before sending.',
		entryKey: 'summarize_topic',
		entry: `  # Parameterized prompt — declare arguments and inject them into the result with the
  # \${argument} notation. The client fills the arguments before sending the prompt.
  summarize_topic:
    title: Summarize a topic
    description: Produce a concise summary of the given topic for the given audience.
    arguments:
      - name: topic
        description: The subject to summarize.
        required: true
      - name: audience
        description: Who the summary is written for (e.g. "executives").
        required: false
    result: |-
      Write a concise summary of "\${topic}".
      Tailor the tone and depth for the following audience: \${audience}.
`
	},
	{
		id: 'tool-guiding-prompt',
		label: 'Tool-guiding prompt',
		description:
			'Steer the model toward the right MCP tool(s) and arguments, turning a fuzzy intent into a concrete, reliable tool call.',
		entryKey: 'get_product_details',
		entry: `  # Tool-guiding prompt — steer the model toward the right MCP tool(s) and arguments,
  # turning a fuzzy user intent into a concrete, reliable tool call.
  get_product_details:
    title: Get details of a product
    description: Fetch full details for a specific product from the catalog.
    arguments:
      - name: name
        description: The name of the product to look up.
        required: true
    result: |-
      Get the detailed information on the product named '\${name}'.
      Call the MCP tool 'get_product_by_name' using '\${name}' as the 'name' argument.
`
	}
];

const RESOURCES_EXAMPLES: ArtifactExample[] = [
	{
		id: 'inline-resource',
		label: 'Inline resource',
		description:
			'Content is embedded in the artifact (text/blob) — reShapr serves it as-is, without ever calling the backend.',
		entryKey: "'file:///docs/overview.md'",
		entry: `  # Inline resource — the content lives in the artifact itself (the text below).
  # reShapr serves it verbatim; no backend call is made on read.
  'file:///docs/overview.md':
    name: overview.md
    title: Service overview
    description: A short, human-readable overview of this service.
    mimeType: text/markdown
    annotations:
      audience:
        - user
      priority: 0.5
    text: |
      # Overview

      This service exposes curated MCP tools and resources.
`
	},
	{
		id: 'backend-resource',
		label: 'Backend resource',
		description:
			'No inline content — reShapr fetches it live from the exposition backend endpoint on each read (GET {backend endpoint}{path}).',
		entryKey: "'file:///data/latest-report.json'",
		entry: `  # Backend resource — no inline content, so reShapr fetches it dynamically from the
  # exposition's backend endpoint on each read: GET {backend endpoint}/data/latest-report.json.
  # The response body becomes the resource content (mimeType is used as a fallback).
  'file:///data/latest-report.json':
    name: latest-report.json
    title: Latest report
    description: Live report fetched from the backend endpoint whenever the resource is read.
    mimeType: application/json
`
	},
	{
		id: 'resource-template',
		label: 'Resource template',
		description:
			'A parameterized URI (RFC 6570) the client expands to fetch a family of related resources dynamically from the backend endpoint.',
		entryKey: "'file:///project/src/{path}'",
		rootKey: 'resourceTemplates',
		entry: `  # Resource template — a parameterized URI (RFC 6570); the client expands {path} to a
  # concrete URI, whose content is then fetched dynamically from the backend endpoint.
  'file:///project/src/{path}':
    name: Project source file
    title: Project files
    description: Access any file under the project source directory.
    mimeType: application/octet-stream
`
	},
	{
		id: 'mcp-app',
		label: 'MCP App',
		description:
			'An interactive HTML UI (MCP-UI) rendered by the client and bound to a tool, with its own content-security-policy.',
		entryKey: "'ui://user/mcp-app.html'",
		entry: `  # MCP App — an interactive HTML UI rendered by the client and bound to a tool.
  # remoteContent is served by your app; _meta.ui declares its CSP.
  'ui://user/mcp-app.html':
    name: User widget
    title: User widget
    description: Interactive widget rendering the result of the get_user tool.
    mimeType: text/html;profile=mcp-app
    remoteContent: http://localhost:3030/mcp-app.html
    _meta:
      ui:
        csp:
          resourceDomains:
            - https://avatars.githubusercontent.com
    tools:
      - get_user:
          visibility:
            - app
            - model
`
	}
];

const TOOLS_OUTPUT_FILTERS_EXAMPLES: ArtifactExample[] = [
	{
		id: 'retain-branches',
		label: 'Retain fields',
		description:
			'Keep only the given JSON Pointer branches of a tool response and drop everything else (jsonRetain).',
		entryKey: 'listRepositories',
		entry: `  # Retain only selected branches — keep just these JSON Pointer paths, drop the rest.
  listRepositories:
    jsonRetain:
      - /data/repositories/nodes/name
      - /data/repositories/nodes/url
`
	},
	{
		id: 'json-patch',
		label: 'JSON Patch edits',
		description:
			'Rewrite a tool response with RFC 6902 JSON Patch operations before it reaches the LLM (jsonPatches).',
		entryKey: 'getUser',
		entry: `  # Rewrite the payload with JSON Patch (RFC 6902) operations.
  getUser:
    jsonPatches:
      - op: remove
        path: /data/user/avatarUrl
      - op: move
        from: /data/user/login
        path: /login
`
	},
	{
		id: 'compact',
		label: 'Compact output',
		description:
			'Recursively drop null values, empty strings, arrays and objects from the tool response (compact).',
		entryKey: 'searchIssues',
		entry: `  # Compact the output by recursively removing sparse values (null, "", [], {}).
  searchIssues:
    compact: true
`
	},
	{
		id: 'convert-to-toon',
		label: 'Convert to TOON',
		description:
			'Convert the final JSON output to the compact, LLM-friendly TOON representation (convertToToon).',
		entryKey: 'listCommits',
		entry: `  # Convert the final JSON output to the compact, LLM-friendly TOON format.
  listCommits:
    convertToToon: true
`
	}
];

const SKILLS_EXAMPLES: ArtifactExample[] = [
	{
		id: 'skills-remote-skill',
		label: 'Simple Skill with files',
		description:
			'Serve an Agent Skill directory over MCP. The gateway fetches SKILL.md and the listed files from remoteContent.',
		entryKey: 'pdf-processing',
		entry: `  # An Agent Skill hosted on an HTTP server, SKILL.md file is required and implied.
  pdf-processing:
    remoteContent: http://localhost:3040/skills/pdf-processing
    files:
      - references/FORMS.md
      - scripts/extract.py
`
	}
];

const EXAMPLES_BY_KIND: Record<ReshaprArtifactKind, ArtifactExample[]> = {
	Prompts: PROMPTS_EXAMPLES,
	CustomTools: CUSTOM_TOOLS_EXAMPLES,
	Resources: RESOURCES_EXAMPLES,
	ToolsOutputFilters: TOOLS_OUTPUT_FILTERS_EXAMPLES,
	Skills: SKILLS_EXAMPLES
};

/** The curated examples available for a given artifact kind (may be empty). */
export function getExamplesForKind(kind: ReshaprArtifactKind): ArtifactExample[] {
	return EXAMPLES_BY_KIND[kind] ?? [];
}

function escapeRegExp(value: string): string {
	return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Whether a two-space indented entry named `key` already exists in the document. */
function entryKeyExists(content: string, key: string): boolean {
	return new RegExp(`^\\s{2}${escapeRegExp(key)}\\s*:`, 'm').test(content);
}

/** Whether the given root map holds a top-level line (block or inline) in the document. */
function rootKeyPresent(content: string, rootKey: string): boolean {
	return new RegExp(`^${escapeRegExp(rootKey)}\\s*:`, 'm').test(content);
}

/** Whether the given root map (block or non-empty inline) already holds an entry. */
function rootHasEntries(content: string, rootKey: string): boolean {
	const lines = content.split('\n');
	const rootLineRe = new RegExp(`^${escapeRegExp(rootKey)}\\s*:`);
	const idx = lines.findIndex((line) => rootLineRe.test(line));
	if (idx === -1) return false;

	const inline = lines[idx].slice(lines[idx].indexOf(':') + 1).trim();
	if (inline && inline !== '{}' && inline !== '{ }') return true;

	for (let i = idx + 1; i < lines.length; i++) {
		const line = lines[i];
		if (/^\s*$/.test(line) || /^\s*#/.test(line)) continue;
		if (/^\s{2,}\S/.test(line)) return true; // an indented child entry
		if (/^\S/.test(line)) break; // reached the next top-level key
	}
	return false;
}

/** Whether any payload root map of the kind already holds an entry. */
function hasAnyPayloadEntries(content: string, kind: ReshaprArtifactKind): boolean {
	return PAYLOAD_ROOT_KEYS_BY_KIND[kind].some((rootKey) => rootHasEntries(content, rootKey));
}

/** Everything before the first payload root key line, or `null` when none is present. */
function headerBeforePayload(content: string, kind: ReshaprArtifactKind): string | null {
	const lines = content.split('\n');
	const rootRes = PAYLOAD_ROOT_KEYS_BY_KIND[kind].map(
		(rootKey) => new RegExp(`^${escapeRegExp(rootKey)}\\s*:`)
	);
	const idx = lines.findIndex((line) => rootRes.some((re) => re.test(line)));
	if (idx === -1) return null;
	return lines.slice(0, idx).join('\n');
}

/** Suffix a (possibly quoted) map key to avoid collisions, keeping any quotes valid. */
function keyWithSuffix(key: string, index: number): string {
	const quote = key.charAt(0);
	if ((quote === "'" || quote === '"') && key.endsWith(quote)) {
		return `${key.slice(0, -1)}-${index}${quote}`;
	}
	return `${key}_${index}`;
}

/** Re-key the example entry so it does not collide with an existing one. */
function uniqueEntry(content: string, example: ArtifactExample): string {
	if (!entryKeyExists(content, example.entryKey)) return example.entry;
	let index = 2;
	while (entryKeyExists(content, keyWithSuffix(example.entryKey, index))) index++;
	const key = keyWithSuffix(example.entryKey, index);
	return example.entry.replace(
		new RegExp(`^(\\s{2})${escapeRegExp(example.entryKey)}\\s*:`, 'm'),
		`$1${key}:`
	);
}

/** Append the entry inside an existing root map section (block or empty-inline). */
function insertIntoSection(content: string, rootKey: string, entry: string): string {
	const lines = content.split('\n');
	const rootLineRe = new RegExp(`^${escapeRegExp(rootKey)}\\s*:`);
	const idx = lines.findIndex((line) => rootLineRe.test(line));
	if (idx === -1) return content;

	const entryLines = entry.replace(/\n$/, '').split('\n');
	const inline = lines[idx].slice(lines[idx].indexOf(':') + 1).trim();
	if (inline === '{}' || inline === '{ }') {
		lines[idx] = `${rootKey}:`;
		lines.splice(idx + 1, 0, ...entryLines);
		return lines.join('\n');
	}

	let end = lines.length;
	for (let i = idx + 1; i < lines.length; i++) {
		if (/^\S/.test(lines[i])) {
			end = i;
			break;
		}
	}
	while (end - 1 > idx && /^\s*$/.test(lines[end - 1])) end--;
	lines.splice(end, 0, ...entryLines);
	return lines.join('\n');
}

/**
 * Insert an example into the current YAML document.
 *
 * - When the document has no payload entry yet (blank editor or empty `rootKey: {}` skeleton),
 *   the whole document is (re)seeded under the example's root map, reusing the existing header.
 * - Otherwise the (key-de-duplicated) example entry is inserted under its root map — appended
 *   inside the section when it already exists, or added as a new section otherwise — so composing
 *   several examples never yields a duplicate mapping key.
 */
export function insertExample(
	content: string,
	kind: ReshaprArtifactKind,
	example: ArtifactExample,
	service: ServiceRef
): string {
	const rootKey = example.rootKey ?? defaultRootKey(kind);

	if (content.trim().length === 0 || !hasAnyPayloadEntries(content, kind)) {
		const header = headerBeforePayload(content, kind) ?? buildHeaderFromTemplate(kind, service);
		const normalizedHeader = header.endsWith('\n') ? header : `${header}\n`;
		return `${normalizedHeader}${rootKey}:\n${example.entry}`;
	}

	const entry = uniqueEntry(content, example);
	if (rootKeyPresent(content, rootKey)) {
		return insertIntoSection(content, rootKey, entry);
	}

	const base = content.endsWith('\n') ? content : `${content}\n`;
	return `${base}${rootKey}:\n${entry}`;
}

/** Derive the header (apiVersion/kind/service) from the minimal template skeleton. */
function buildHeaderFromTemplate(kind: ReshaprArtifactKind, service: ServiceRef): string {
	const skeleton = buildTemplate(kind, service);
	return headerBeforePayload(skeleton, kind) ?? '';
}
