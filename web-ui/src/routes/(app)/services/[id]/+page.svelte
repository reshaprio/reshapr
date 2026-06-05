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
	import { apiClient } from '$lib/api/client.js';
	import JsonBlock from '$lib/components/JsonBlock.svelte';
	import { loadServiceHubSummary, type ServiceHubSummary } from '$lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import * as Collapsible from '$lib/components/ui/collapsible/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { cn } from '$lib/utils.js';
	import ChevronDownIcon from '@lucide/svelte/icons/chevron-down';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	let payloadOpen = $state(false);
	let summary = $state<ServiceHubSummary | null>(null);
	let summaryError = $state<string | null>(null);
	let summaryLoading = $state(false);

	async function loadSummary() {
		if (!ctx.id) return;
		summaryLoading = true;
		summaryError = null;
		try {
			summary = await loadServiceHubSummary(ctx.id, apiClient());
		} catch (e) {
			summary = null;
			summaryError = e instanceof Error ? e.message : String(e);
		} finally {
			summaryLoading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading && ctx.service) {
			void loadSummary();
		}
	});

	function fmtCount(n: number | null | undefined): string {
		if (summaryLoading) return '…';
		if (n == null) return '—';
		return String(n);
	}
</script>

{#if summaryError}
	<p class="text-destructive mb-4 text-sm">{summaryError}</p>
{/if}

<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
	<Card.Root>
		<Card.Header class="pb-2">
			<Card.Title class="text-sm font-medium">Artifacts</Card.Title>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmtCount(summary?.artifactCount)}</p>
			<Button variant="link" class="mt-2 h-auto p-0 text-xs" href="/services/{ctx.id}/artifacts">
				View artifacts
			</Button>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="pb-2">
			<Card.Title class="text-sm font-medium">Configuration plans</Card.Title>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmtCount(summary?.planCount)}</p>
			<Button variant="link" class="mt-2 h-auto p-0 text-xs" href="/services/{ctx.id}/plans">
				View plans
			</Button>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="pb-2">
			<Card.Title class="text-sm font-medium">Expositions</Card.Title>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">
				{fmtCount(summary?.expositionActiveCount)}
				<span class="text-muted-foreground text-lg font-normal">
					/ {fmtCount(summary?.expositionAllCount)} total
				</span>
			</p>
			<p class="text-muted-foreground text-xs">Active / all</p>
			<Button variant="link" class="mt-2 h-auto p-0 text-xs" href="/services/{ctx.id}/expositions">
				View expositions
			</Button>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="pb-2">
			<Card.Title class="text-sm font-medium">MCP custom tools</Card.Title>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmtCount(summary?.mcpCustomToolsCount)}</p>
			<Button
				variant="link"
				class="mt-2 h-auto p-0 text-xs"
				href="/services/{ctx.id}/mcp-custom-tools"
			>
				View tools
			</Button>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="pb-2">
			<Card.Title class="text-sm font-medium">MCP prompts</Card.Title>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmtCount(summary?.mcpPromptsCount)}</p>
			<Button variant="link" class="mt-2 h-auto p-0 text-xs" href="/services/{ctx.id}/mcp-prompts">
				View prompts
			</Button>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="pb-2">
			<Card.Title class="text-sm font-medium">MCP endpoints</Card.Title>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmtCount(summary?.mcpUrlCount)}</p>
			<p class="text-muted-foreground text-xs">From active expositions</p>
		</Card.Content>
	</Card.Root>

	{#if ctx.service}
		<Card.Root>
			<Card.Header class="pb-2">
				<Card.Title class="text-sm font-medium">API operations</Card.Title>
			</Card.Header>
			<Card.Content>
				<p class="text-3xl font-bold tracking-tight">{ctx.service.operationsCount}</p>
				<p class="text-muted-foreground text-xs">Registered on service</p>
			</Card.Content>
		</Card.Root>
	{/if}
</div>

<Collapsible.Root bind:open={payloadOpen} class="mt-8 rounded-lg border">
	<Collapsible.Trigger
		class="flex w-full items-center justify-between gap-3 px-4 py-3 text-left text-sm font-semibold transition-colors hover:bg-muted/50"
		type="button"
	>
		<span>Service payload</span>
		<ChevronDownIcon
			class={cn(
				'text-muted-foreground size-4 shrink-0 transition-transform duration-200',
				payloadOpen && 'rotate-180'
			)}
		/>
	</Collapsible.Trigger>
	<Collapsible.Content class="border-t px-4 pb-4 pt-3">
		<JsonBlock value={ctx.raw} loading={ctx.loading} />
	</Collapsible.Content>
</Collapsible.Root>
