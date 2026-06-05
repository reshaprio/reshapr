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
	import { collectMcpUrlsFromActiveExpositions } from '$lib/mcpEndpointUrls.js';
	import { expositionBelongsToService } from '$lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	type ExpoRow = {
		id: string;
		scope: string;
		backend: string;
		mcpUrls: number;
	};

	let rows = $state<ExpoRow[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);
	let mode = $state<'active' | 'all'>('active');

	function backendUrl(configurationPlan: unknown): string {
		if (!configurationPlan || typeof configurationPlan !== 'object') return '—';
		const c = configurationPlan as Record<string, unknown>;
		return typeof c.backendEndpoint === 'string' ? c.backendEndpoint : '—';
	}

	function toRow(raw: unknown, scope: string): ExpoRow | null {
		if (!raw || typeof raw !== 'object') return null;
		const o = raw as Record<string, unknown>;
		if (typeof o.id !== 'string') return null;
		const urls = collectMcpUrlsFromActiveExpositions(
			scope === 'active' ? [raw] : raw,
		);
		return {
			id: o.id,
			scope,
			backend: backendUrl(o.configurationPlan),
			mcpUrls: scope === 'active' ? urls.length : 0
		};
	}

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		try {
			const c = apiClient();
			if (mode === 'active') {
				const active = await c.listExpositionsActive();
				const list = (Array.isArray(active) ? active : []).filter((e) =>
					expositionBelongsToService(e, ctx.id),
				);
				rows = list
					.map((e) => toRow(e, 'active'))
					.filter((r): r is ExpoRow => r != null);
			} else {
				const all = await c.listExpositionsAll();
				const list = (Array.isArray(all) ? all : []).filter((e) =>
					expositionBelongsToService(e, ctx.id),
				);
				rows = list
					.map((e) => toRow(e, 'all'))
					.filter((r): r is ExpoRow => r != null);
			}
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			rows = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		mode;
		if (ctx.id && !ctx.loading) void load();
	});
</script>

<div class="mb-4 flex flex-wrap items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">Expositions</h3>
	<div class="flex flex-wrap items-center gap-2">
		<label class="flex items-center gap-2 text-sm">
			<input type="radio" bind:group={mode} value="active" />
			Active
		</label>
		<label class="flex items-center gap-2 text-sm">
			<input type="radio" bind:group={mode} value="all" />
			All
		</label>
		<Button variant="outline" size="sm" disabled={loading} onclick={() => void load()}>Refresh</Button>
	</div>
</div>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>Scope</Table.Head>
				<Table.Head>Backend</Table.Head>
				<Table.Head>MCP URLs</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#if loading}
				<Table.Row>
					<Table.Cell colspan={4} class="text-muted-foreground">Loading…</Table.Cell>
				</Table.Row>
			{:else if rows.length === 0}
				<Table.Row>
					<Table.Cell colspan={4} class="text-muted-foreground">No expositions for this service.</Table.Cell>
				</Table.Row>
			{:else}
				{#each rows as e (e.id + e.scope)}
					<Table.Row>
						<Table.Cell>
							<a href="/expositions/{e.id}" class="text-primary hover:underline">{e.id}</a>
						</Table.Cell>
						<Table.Cell>{e.scope}</Table.Cell>
						<Table.Cell class="max-w-xs truncate" title={e.backend}>{e.backend}</Table.Cell>
						<Table.Cell>{e.mcpUrls || '—'}</Table.Cell>
					</Table.Row>
				{/each}
			{/if}
		</Table.Body>
	</Table.Root>
</div>
