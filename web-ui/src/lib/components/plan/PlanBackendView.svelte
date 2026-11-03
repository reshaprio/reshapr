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

<!-- Shared display of a configuration plan's backend (endpoint, auth, timeout). -->
<script lang="ts">
	import { Badge } from '#lib/components/ui/badge/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { SquareLock01Icon, Key01Icon, Timer01Icon } from '@hugeicons/core-free-icons';
	import { backendEndpointOf, backendTimeoutOf, type BackendSecret } from './planView.js';

	let {
		plan,
		backendSecret = null
	}: { plan: Record<string, unknown> | null; backendSecret?: BackendSecret | null } = $props();

	const backendEndpoint = $derived(backendEndpointOf(plan));
	const backendTimeout = $derived(backendTimeoutOf(plan));
</script>

<div class="space-y-4 text-sm">
	<div>
		<div class="text-muted-foreground mb-1 text-xs">Endpoint</div>
		<code
			class="bg-muted block w-full overflow-x-auto rounded-md px-2 py-1.5 font-mono text-xs"
			title={backendEndpoint ?? ''}
		>
			{backendEndpoint ?? '—'}
		</code>
	</div>

	<!-- Backend authentication -->
	<div>
		<div class="text-muted-foreground mb-1 flex items-center gap-1.5 text-xs">
			<HugeiconsIcon icon={SquareLock01Icon} size={13} />
			<span>Authentication</span>
		</div>
		{#if backendSecret}
			<div class="flex flex-wrap items-center gap-2">
				<Badge variant="secondary" class="gap-1">
					<HugeiconsIcon icon={Key01Icon} size={12} />
					{backendSecret.name}
				</Badge>
				{#if backendSecret.type}
					<span class="text-muted-foreground text-[10px] uppercase">{backendSecret.type}</span>
				{/if}
			</div>
			<p class="text-muted-foreground mt-1 text-xs">
				Credentials injected from a stored secret reference.
			</p>
		{:else}
			<span class="text-muted-foreground text-xs">
				No authentication — the backend is called anonymously.
			</span>
		{/if}
	</div>

	<div class="border-t pt-4">
		<div class="text-muted-foreground mb-1 flex items-center gap-1.5 text-xs">
			<HugeiconsIcon icon={Timer01Icon} size={13} /> Timeout
		</div>
		<div class="text-muted-foreground">{backendTimeout ?? 'default'}</div>
	</div>
</div>

