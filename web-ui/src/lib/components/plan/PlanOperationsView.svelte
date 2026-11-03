<!--
  ~ Copyright The Reshapr Authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~ http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<!-- Shared display of a configuration plan's exposed operations. -->
<script lang="ts">
	import { cn } from '#lib/utils.js';
	import { Badge } from '#lib/components/ui/badge/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Route01Icon } from '@hugeicons/core-free-icons';
	import { planOpsMode, planOperations, splitOp, methodStyle } from './planView.js';

	let { plan }: { plan: Record<string, unknown> | null } = $props();

	const opsMode = $derived(planOpsMode(plan));
	const operations = $derived(planOperations(plan));
</script>

<div>
	<div class="mb-2 flex items-center gap-2">
		<HugeiconsIcon icon={Route01Icon} size={15} class="text-muted-foreground" />
		<span class="font-medium">Operations</span>
		{#if opsMode === 'exclude'}
			<Badge variant="outline" class="text-[10px] uppercase">excluded</Badge>
		{:else if opsMode === 'include'}
			<span class="text-muted-foreground text-xs">{operations.length}</span>
		{/if}
	</div>
	{#if opsMode === 'all'}
		<p class="text-muted-foreground text-xs">
			No filter — all operations of the service are exposed.
		</p>
	{:else}
		{#if opsMode === 'exclude'}
			<p class="text-muted-foreground mb-2 text-xs">
				All operations except the following are exposed:
			</p>
		{/if}
		<div class="flex flex-wrap gap-1.5">
			{#each operations as op (op)}
				{@const parts = splitOp(op)}
				<span
					class="bg-muted/50 inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 font-mono text-xs"
					title={op}
				>
					{#if parts.method}
						<span
							class={cn(
								'rounded px-1 text-[10px] font-bold ring-1 ring-inset',
								methodStyle(parts.method)
							)}>{parts.method}</span
						>
					{/if}
					<span class="max-w-[16rem] truncate">{parts.rest}</span>
				</span>
			{/each}
		</div>
	{/if}
</div>

