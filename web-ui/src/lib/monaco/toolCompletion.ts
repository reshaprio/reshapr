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

import type * as Monaco from 'monaco-editor';
import type { ReshaprArtifactKind } from '#lib/artifacts/types.js';
import type { ToolSuggestion } from '#lib/artifacts/validators.js';

/** Returns the typed word range if the cursor sits where a tool name is expected, otherwise null. */
function toolNameContext(
	kind: ReshaprArtifactKind,
	model: Monaco.editor.ITextModel,
	position: Monaco.Position
): { startColumn: number } | null {
	const linePrefix = model.getLineContent(position.lineNumber).slice(0, position.column - 1);

	if (kind === 'CustomTools') {
		// `tool: <name>` (declarative form), also inside a `tools:` sequence item (`- tool: <name>`).
		const m = /^\s*(?:-\s+)?tool:\s+(["']?)([^\s"']*)$/.exec(linePrefix);
		if (!m) return null;
		return { startColumn: position.column - m[2].length };
	}

	if (kind === 'ToolsOutputFilters') {
		// A two-space indented key directly under the root `filters:` map.
		const m = /^ {2}(["']?)([^\s:"'#-][^\s:"']*)?$/.exec(linePrefix);
		if (!m) return null;
		for (let line = position.lineNumber - 1; line >= 1; line--) {
			const content = model.getLineContent(line);
			if (/^\s*(#.*)?$/.test(content)) continue;
			if (/^\S/.test(content)) {
				if (!/^filters\s*:/.test(content)) return null;
				return { startColumn: position.column - (m[2]?.length ?? 0) };
			}
		}
		return null;
	}

	return null;
}

/** Registers tool-name completion for the given model; returns a disposable. */
export function registerToolCompletion(
	monaco: typeof Monaco,
	model: Monaco.editor.ITextModel,
	kind: ReshaprArtifactKind,
	getSuggestions: () => Promise<ToolSuggestion[]>
): Monaco.IDisposable {
	return monaco.languages.registerCompletionItemProvider('yaml', {
		async provideCompletionItems(m, position) {
			if (m.uri.toString() !== model.uri.toString()) return { suggestions: [] };
			const ctx = toolNameContext(kind, m, position);
			if (!ctx) return { suggestions: [] };

			const suggestions = await getSuggestions();
			const range = new monaco.Range(
				position.lineNumber,
				ctx.startColumn,
				position.lineNumber,
				position.column
			);
			return {
				suggestions: suggestions.map((tool) => ({
					label: tool.name,
					kind: monaco.languages.CompletionItemKind.Function,
					detail: tool.detail,
					insertText: tool.name,
					range,
					sortText: `${tool.origin === 'operation' ? '0' : '1'}_${tool.name}`
				}))
			};
		}
	});
}
