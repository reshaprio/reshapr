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

<!-- Shared display of a configuration plan's capabilities (custom artifacts). -->
<script lang="ts">
	import { cn } from '#lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Wrench01Icon } from '@hugeicons/core-free-icons';
	import type { ArtifactRef } from '#lib/artifacts/index.js';
	import { capabilityGroups, CAPABILITY_ICONS, CAPABILITY_STYLES } from './planView.js';

	let {
		plan,
		artifacts
	}: { plan: Record<string, unknown> | null; artifacts: ArtifactRef[] } = $props();

	const groups = $derived(capabilityGroups(plan, artifacts));
	const total = $derived(groups.reduce((n, g) => n + g.items.length, 0));
</script>

<div>
	<div class="mb-2 flex items-center gap-2">
		<HugeiconsIcon icon={Wrench01Icon} size={15} class="text-muted-foreground" />
		<span class="font-medium">Capabilities</span>
		<span class="text-muted-foreground text-xs">{total}</span>
	</div>
	{#if groups.length === 0}
		<p class="text-muted-foreground text-xs">
			No custom tools, prompts, resources or output filters attached.
		</p>
	{:else}
		<div class="space-y-3">
			{#each groups as group (group.type)}
				{@const Icon = CAPABILITY_ICONS[group.type]}
				<div>
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						{#if Icon}<HugeiconsIcon icon={Icon} size={13} />{/if}
						<span>{group.label}</span>
						<span class="opacity-70">{group.items.length}</span>
					</div>
					<div class="flex flex-wrap gap-1.5">
						{#each group.items as item (group.type + '::' + item.artifactName + '::' + item.name)}
							<span
								class={cn(
									'inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs ring-1 ring-inset',
									CAPABILITY_STYLES[group.type] ?? 'bg-muted text-muted-foreground ring-border'
								)}
								title={`Defined by artifact ${item.artifactName}`}
							>
								{item.name}
							</span>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>

