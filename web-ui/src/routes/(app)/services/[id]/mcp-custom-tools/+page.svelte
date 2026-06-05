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
	import {
		resolveMcpCustomToolsForService,
		type McpCustomToolsResolution
	} from '$lib/mcpCustomTools.js';
	import { collectMcpUrlsFromActiveExpositions } from '$lib/mcpEndpointUrls.js';
	import { expositionBelongsToService } from '$lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import * as Collapsible from '$lib/components/ui/collapsible/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	let result = $state<McpCustomToolsResolution | null>(null);
	let mcpUrls = $state<{ url: string; fqdn: string }[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(false);
	let yamlOpen = $state(false);

	const tools = $derived(result?.tools ?? []);

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		result = null;
		try {
			const c = apiClient();
			const active = await c.listExpositionsActive();
			const forService = (Array.isArray(active) ? active : []).filter((e) =>
				expositionBelongsToService(e, ctx.id),
			);
			mcpUrls = collectMcpUrlsFromActiveExpositions(forService).map((u) => ({
				url: u.url,
				fqdn: u.fqdn
			}));
			result = await resolveMcpCustomToolsForService(ctx.id, c);
			yamlOpen = Boolean(result.artifactYaml);
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
	<h3 class="text-lg font-semibold">MCP custom tools</h3>
	<Button variant="outline" size="sm" disabled={loading} onclick={() => void load()}>Refresh</Button>
</div>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

{#if result}
	<div class="text-muted-foreground mb-4 flex flex-wrap items-center gap-2 text-sm">
		<Badge variant="secondary">{result.source}</Badge>
		{#if result.expoId}
			<span>Exposition <code class="text-xs">{result.expoId}</code></span>
		{/if}
		<span>{tools.length} tool(s)</span>
	</div>
{/if}

{#if mcpUrls.length > 0}
	<Card.Root class="mb-6">
		<Card.Header>
			<Card.Title class="text-base">MCP endpoints</Card.Title>
		</Card.Header>
		<Card.Content class="space-y-2">
			{#each mcpUrls as u (u.url)}
				<ScrollableCode text={u.url} maxHeight="3rem" />
			{/each}
		</Card.Content>
	</Card.Root>
{/if}

{#if result?.artifactYaml}
	<Collapsible.Root bind:open={yamlOpen} class="mb-6">
		<Collapsible.Trigger class="text-primary text-sm font-medium hover:underline" type="button">
			{yamlOpen ? 'Hide' : 'Show'} RESHAPR_CUSTOM_TOOLS YAML
		</Collapsible.Trigger>
		<Collapsible.Content class="mt-2">
			<ScrollableCode text={result.artifactYaml} maxHeight="24rem" />
		</Collapsible.Content>
	</Collapsible.Root>
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>Name</Table.Head>
				<Table.Head>Description</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#if loading}
				<Table.Row>
					<Table.Cell colspan={2} class="text-muted-foreground">Loading…</Table.Cell>
				</Table.Row>
			{:else if tools.length === 0}
				<Table.Row>
					<Table.Cell colspan={2} class="text-muted-foreground">No custom tools resolved.</Table.Cell>
				</Table.Row>
			{:else}
				{#each tools as t (t.name)}
					<Table.Row>
						<Table.Cell class="font-mono text-sm">{t.name}</Table.Cell>
						<Table.Cell class="text-sm">{t.description || '—'}</Table.Cell>
					</Table.Row>
				{/each}
			{/if}
		</Table.Body>
	</Table.Root>
</div>
