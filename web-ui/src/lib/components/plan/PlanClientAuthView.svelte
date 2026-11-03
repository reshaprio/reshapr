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

<!-- Shared display of the MCP endpoint (client-facing) authentication. -->
<script lang="ts">
	import { Badge } from '#lib/components/ui/badge/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		UserShield01Icon,
		ShieldEnergyIcon,
		Key01Icon,
		GlobalIcon
	} from '@hugeicons/core-free-icons';
	import { mcpAuthOf } from './planView.js';

	let { plan }: { plan: Record<string, unknown> | null } = $props();

	const mcpAuth = $derived(mcpAuthOf(plan));
</script>

<div>
	<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
		<HugeiconsIcon icon={UserShield01Icon} size={13} /> Client authentication
	</div>
	{#if mcpAuth.kind === 'oauth'}
		<div class="flex items-center gap-2">
			<Badge class="bg-primary/10 text-primary gap-1 border-transparent">
				<HugeiconsIcon icon={ShieldEnergyIcon} size={12} /> OAuth 2.0
			</Badge>
		</div>
		{#if mcpAuth.servers.length}
			<dl class="mt-2 space-y-1 text-xs">
				<div>
					<dt class="text-muted-foreground">Authorization servers</dt>
					<dd class="mt-0.5 flex flex-wrap gap-1">
						{#each mcpAuth.servers as s (s)}
							<code class="bg-muted rounded px-1 py-0.5 font-mono break-all">{s}</code>
						{/each}
					</dd>
				</div>
			</dl>
		{/if}
		{#if mcpAuth.scopes.length}
			<div class="mt-2 text-xs">
				<span class="text-muted-foreground">Scopes: </span>
				{#each mcpAuth.scopes as sc (sc)}
					<code class="bg-muted mr-1 rounded px-1 py-0.5 font-mono">{sc}</code>
				{/each}
			</div>
		{/if}
	{:else if mcpAuth.kind === 'apikey'}
		<Badge variant="secondary" class="gap-1">
			<HugeiconsIcon icon={Key01Icon} size={12} /> API key
		</Badge>
		<p class="text-muted-foreground mt-1 text-xs">
			Clients must present the plan API key to reach this server.
		</p>
	{:else}
		<Badge variant="outline" class="gap-1">
			<HugeiconsIcon icon={GlobalIcon} size={12} /> Public
		</Badge>
		<p class="text-muted-foreground mt-1 text-xs">No client authentication required.</p>
	{/if}
</div>

