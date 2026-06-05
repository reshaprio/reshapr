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

/** Parse CLI-style operation lists (--io / --eo): JSON array or one operation per line. */
export function parseOperationsList(text: string): string[] {
	const trimmed = text.trim();
	if (!trimmed) return [];

	if (trimmed.startsWith('[')) {
		const parsed = JSON.parse(trimmed) as unknown;
		if (!Array.isArray(parsed)) {
			throw new Error('Operations must be a JSON array of strings.');
		}
		return parsed.map(String).filter((s) => s.length > 0);
	}

	return trimmed
		.split(/\r?\n/)
		.map((line) => line.trim())
		.filter((line) => line.length > 0 && !line.startsWith('#'));
}

export function formatOperationsList(ops: unknown): string {
	if (ops == null) return '';
	if (Array.isArray(ops)) {
		return ops.map(String).filter(Boolean).join('\n');
	}
	return '';
}
