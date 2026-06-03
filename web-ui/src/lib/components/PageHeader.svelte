<script lang="ts">
	import type { Snippet } from 'svelte';

	let {
		title,
		actions
	}: {
		title: string;
		actions?: Snippet;
	} = $props();
</script>

<div class="mb-6 flex flex-wrap items-center justify-between gap-4 border-b pb-4">
	<h2 class="text-xl font-bold tracking-tight text-foreground">{title}</h2>
	{#if actions}
		<div class="flex flex-wrap items-center gap-2">
			{@render actions()}
		</div>
	{/if}
</div>
