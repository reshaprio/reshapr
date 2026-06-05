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
	import * as Card from '$lib/components/ui/card/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';

	type Gg = { id: string; name: string; organizationId?: string; labels?: Record<string, string> };

	let rows = $state<Gg[]>([]);
	let error = $state<string | null>(null);

	async function load() {
		error = null;
		try {
			const data = (await apiClient().listGatewayGroups()) as Gg[];
			rows = Array.isArray(data) ? data : [];
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	$effect(() => {
		void load();
	});

	async function onCreate(ev: SubmitEvent) {
		ev.preventDefault();
		const form = ev.target as HTMLFormElement;
		const fd = new FormData(form);
		const name = String(fd.get('name') || '');
		let labels: Record<string, string> = {};
		const lj = String(fd.get('labels') || '').trim();
		if (lj) {
			try {
				labels = JSON.parse(lj) as Record<string, string>;
			} catch {
				error = 'Labels: invalid JSON';
				return;
			}
		}
		error = null;
		try {
			await apiClient().createGatewayGroup({ name, labels });
			form.reset();
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onDelete(id: string) {
		if (!confirm(`Delete group ${id}?`)) return;
		error = null;
		try {
			await apiClient().deleteGatewayGroup(id);
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<PageHeader title="Gateway groups">
	{#snippet actions()}
		<Button variant="outline" onclick={() => void load()}>Refresh</Button>
	{/snippet}
</PageHeader>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<Card.Root class="mb-6">
	<Card.Header>
		<Card.Title>New group</Card.Title>
	</Card.Header>
	<Card.Content>
		<form class="space-y-4" onsubmit={onCreate}>
			<Input name="name" placeholder="Name" required />
			<Textarea name="labels" rows={3} placeholder={'Labels JSON e.g. {"env":"dev"}'} />
			<Button type="submit">Create</Button>
		</form>
	</Card.Content>
</Card.Root>

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>Org</Table.Head>
				<Table.Head>Name</Table.Head>
				<Table.Head>Labels</Table.Head>
				<Table.Head class="w-[100px]" />
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#each rows as g (g.id)}
				<Table.Row>
					<Table.Cell>{g.id}</Table.Cell>
					<Table.Cell>{g.organizationId}</Table.Cell>
					<Table.Cell>{g.name}</Table.Cell>
					<Table.Cell>
						<code class="text-xs">{JSON.stringify(g.labels ?? {})}</code>
					</Table.Cell>
					<Table.Cell>
						<Button variant="destructive" size="sm" onclick={() => void onDelete(g.id)}>
							Delete
						</Button>
					</Table.Cell>
				</Table.Row>
			{/each}
		</Table.Body>
	</Table.Root>
</div>
