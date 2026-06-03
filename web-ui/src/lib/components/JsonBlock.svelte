<script lang="ts">
	let { value, loading = false }: { value: unknown; loading?: boolean } = $props();

	const text = $derived(
		loading ? 'Loading…' : value !== undefined && value !== null ? JSON.stringify(value, null, 2) : '—'
	);
</script>

<pre class="bg-muted overflow-x-auto rounded-lg border p-4 font-mono text-xs leading-relaxed">{text}</pre>
