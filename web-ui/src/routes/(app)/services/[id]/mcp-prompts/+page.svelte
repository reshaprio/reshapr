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

<script lang="ts">
	import { getContext } from 'svelte';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import ScrollableCode from '$lib/components/ScrollableCode.svelte';
	import { resolveMcpPromptsForService, type McpPromptsResolution } from '$lib/mcpPrompts.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import * as Collapsible from '$lib/components/ui/collapsible/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	let result = $state<McpPromptsResolution | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(false);
	let yamlOpen = $state(false);

	const prompts = $derived(result?.prompts ?? []);

	function formatArguments(
		args: { name: string; description?: string; required?: boolean }[] | undefined,
	): string {
		if (!args?.length) return '—';
		return args
			.map((a) => {
				const bits = [a.name];
				if (a.required) bits.push('required');
				if (a.description) bits.push(a.description);
				return bits.join(' — ');
			})
			.join('; ');
	}

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		result = null;
		try {
			result = await resolveMcpPromptsForService(ctx.id, apiClient());
			yamlOpen = result.artifactYamls.length > 0;
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading) void load();
	});
</script>

<div class="mb-4 flex items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">MCP prompts</h3>
	<Button variant="outline" size="sm" disabled={loading} onclick={() => void load()}>Refresh</Button>
</div>

<p class="text-muted-foreground mb-4 text-sm">
	Resolved from <code class="text-xs">RESHAPR_PROMPTS</code> artifacts on this service. Attach prompts via
	<a href="/artifacts" class="text-primary hover:underline">Artifacts</a>.
</p>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

{#if result}
	<div class="text-muted-foreground mb-4 flex flex-wrap items-center gap-2 text-sm">
		<Badge variant="secondary">{result.source}</Badge>
		<span>{prompts.length} prompt(s)</span>
		{#if result.artifactNames.length}
			<span>Artifacts: {result.artifactNames.join(', ')}</span>
		{/if}
	</div>
{/if}

{#if result && result.artifactYamls.length > 0}
	<Collapsible.Root bind:open={yamlOpen} class="mb-6">
		<Collapsible.Trigger class="text-primary text-sm font-medium hover:underline" type="button">
			{yamlOpen ? 'Hide' : 'Show'} artifact YAML
		</Collapsible.Trigger>
		<Collapsible.Content class="mt-2 space-y-4">
			{#each result.artifactYamls as art (art.name)}
				<div>
					<p class="mb-1 text-sm font-medium">{art.name}</p>
					<ScrollableCode text={art.content} maxHeight="16rem" />
				</div>
			{/each}
		</Collapsible.Content>
	</Collapsible.Root>
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>Name</Table.Head>
				<Table.Head>Description</Table.Head>
				<Table.Head>Arguments</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#if loading}
				<Table.Row>
					<Table.Cell colspan={3} class="text-muted-foreground">Loading…</Table.Cell>
				</Table.Row>
			{:else if prompts.length === 0}
				<Table.Row>
					<Table.Cell colspan={3} class="text-muted-foreground">No prompts.</Table.Cell>
				</Table.Row>
			{:else}
				{#each prompts as p (p.name)}
					<Table.Row>
						<Table.Cell class="font-mono text-sm">{p.name}</Table.Cell>
						<Table.Cell class="text-sm">{p.description ?? '—'}</Table.Cell>
						<Table.Cell class="text-sm">{formatArguments(p.arguments)}</Table.Cell>
					</Table.Row>
				{/each}
			{/if}
		</Table.Body>
	</Table.Root>
</div>
