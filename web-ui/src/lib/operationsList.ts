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
