<script lang="ts">
	import { cn } from '$lib/utils.js';

	let {
		text,
		class: className = '',
		maxHeight = '5.5rem'
	}: {
		text: string;
		class?: string;
		/** CSS length, e.g. `5.5rem` or `24rem` */
		maxHeight?: string;
	} = $props();
</script>

<div
	class={cn(
		'bg-muted/40 overflow-auto rounded-md border px-2 py-1.5',
		className
	)}
	style:max-height={maxHeight}
	title={text}
>
	<code class="text-foreground block font-mono text-xs leading-relaxed break-all whitespace-pre-wrap"
		>{text}</code>
</div>
