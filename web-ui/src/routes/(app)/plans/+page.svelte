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
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	type PlanRow = {
		id: string;
		name: string;
		serviceId: string;
		backendEndpoint: string;
	};

	let rows = $state<PlanRow[]>([]);
	let error = $state<string | null>(null);

	async function load() {
		error = null;
		try {
			const data = (await apiClient().listConfigurationPlans()) as PlanRow[];
			rows = Array.isArray(data) ? data : [];
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	$effect(() => {
		void load();
	});
</script>

<PageHeader title="Configuration plans">
	{#snippet actions()}
		<Button variant="outline" onclick={() => void load()}>Refresh</Button>
		<Button href="/plans/new">New plan</Button>
	{/snippet}
</PageHeader>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>Name</Table.Head>
				<Table.Head>Service</Table.Head>
				<Table.Head>Backend</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#each rows as p (p.id)}
				<Table.Row>
					<Table.Cell>
						<a href="/plans/{p.id}" class="text-primary hover:underline">{p.id}</a>
					</Table.Cell>
					<Table.Cell>{p.name}</Table.Cell>
					<Table.Cell>{p.serviceId}</Table.Cell>
					<Table.Cell class="max-w-xs truncate" title={p.backendEndpoint}>
						{p.backendEndpoint}
					</Table.Cell>
				</Table.Row>
			{/each}
		</Table.Body>
	</Table.Root>
</div>

{#if rows.length === 0 && !error}
	<p class="text-muted-foreground mt-4 text-sm">No plans.</p>
{/if}
