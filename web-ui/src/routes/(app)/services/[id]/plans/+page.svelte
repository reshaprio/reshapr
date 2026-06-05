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
	import { planBelongsToService } from '$lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	type PlanRow = {
		id: string;
		name: string;
		backendEndpoint: string;
	};

	let rows = $state<PlanRow[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);

	function toRow(raw: unknown): PlanRow | null {
		if (!raw || typeof raw !== 'object') return null;
		const o = raw as Record<string, unknown>;
		if (typeof o.id !== 'string') return null;
		return {
			id: o.id,
			name: typeof o.name === 'string' ? o.name : '—',
			backendEndpoint: typeof o.backendEndpoint === 'string' ? o.backendEndpoint : '—'
		};
	}

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		try {
			const all = await apiClient().listConfigurationPlans();
			const list = (Array.isArray(all) ? all : []).filter((p) => planBelongsToService(p, ctx.id));
			rows = list.map(toRow).filter((r): r is PlanRow => r != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			rows = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading) void load();
	});
</script>

<div class="mb-4 flex flex-wrap items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">Configuration plans</h3>
	<Button variant="outline" size="sm" disabled={loading} onclick={() => void load()}>Refresh</Button>
</div>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>Name</Table.Head>
				<Table.Head>Backend</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#if loading}
				<Table.Row>
					<Table.Cell colspan={3} class="text-muted-foreground">Loading…</Table.Cell>
				</Table.Row>
			{:else if rows.length === 0}
				<Table.Row>
					<Table.Cell colspan={3} class="text-muted-foreground">No plans for this service.</Table.Cell>
				</Table.Row>
			{:else}
				{#each rows as p (p.id)}
					<Table.Row>
						<Table.Cell>
							<a href="/plans/{p.id}" class="text-primary hover:underline">{p.id}</a>
						</Table.Cell>
						<Table.Cell>{p.name}</Table.Cell>
						<Table.Cell class="max-w-xs truncate" title={p.backendEndpoint}>
							{p.backendEndpoint}
						</Table.Cell>
					</Table.Row>
				{/each}
			{/if}
		</Table.Body>
	</Table.Root>
</div>
