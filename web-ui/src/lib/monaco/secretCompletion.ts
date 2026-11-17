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
import type { SecretSuggestion } from '#lib/artifacts/validators.js';

/** Returns the typed word range if the cursor sits on a `remoteSecret:` value in Resources/Skills. */
function secretNameContext(
	kind: ReshaprArtifactKind,
	model: Monaco.editor.ITextModel,
	position: Monaco.Position
): { startColumn: number } | null {
	if (kind !== 'Resources' && kind !== 'Skills') return null;

	const linePrefix = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
	const m = /^\s*remoteSecret:\s+(["']?)([^\s"']*)$/.exec(linePrefix);
	if (!m) return null;
	return { startColumn: position.column - m[2].length };
}

/** Registers `remoteSecret` name completion for the given model; returns a disposable. */
export function registerSecretCompletion(
	monaco: typeof Monaco,
	model: Monaco.editor.ITextModel,
	kind: ReshaprArtifactKind,
	getSuggestions: () => Promise<SecretSuggestion[]>
): Monaco.IDisposable {
	return monaco.languages.registerCompletionItemProvider('yaml', {
		async provideCompletionItems(m, position) {
			if (m.uri.toString() !== model.uri.toString()) return { suggestions: [] };
			const ctx = secretNameContext(kind, m, position);
			if (!ctx) return { suggestions: [] };

			const suggestions = await getSuggestions();
			const range = new monaco.Range(
				position.lineNumber,
				ctx.startColumn,
				position.lineNumber,
				position.column
			);
			return {
				suggestions: suggestions.map((secret) => ({
					label: secret.name,
					kind: monaco.languages.CompletionItemKind.Reference,
					detail: secret.detail,
					insertText: secret.name,
					range,
					sortText: secret.name
				}))
			};
		}
	});
}

