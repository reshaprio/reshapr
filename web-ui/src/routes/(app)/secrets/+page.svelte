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
	import { apiClient } from '$lib/api/client.js';
	import { formatApiError } from '$lib/format-api-error.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	type SecretRefRow = {
		id?: string;
		organizationId?: string;
		name?: string;
		description?: string;
		type?: string;
	};

	let rows = $state<SecretRefRow[]>([]);
	let error = $state<string | null>(null);

	function asRefRows(data: unknown[]): SecretRefRow[] {
		return data.filter((r): r is SecretRefRow => r !== null && typeof r === 'object');
	}

	async function load() {
		error = null;
		try {
			const data = await apiClient().listSecretRefs();
			rows = asRefRows(Array.isArray(data) ? data : []);
		} catch (e) {
			error = formatApiError(e);
		}
	}

	$effect(() => {
		void load();
	});
</script>

<PageHeader title="Secrets">
	{#snippet actions()}
		<Button variant="outline" onclick={() => void load()}>Refresh</Button>
	{/snippet}
</PageHeader>

<p class="text-muted-foreground mb-4 text-sm">
	Same data as <code class="text-xs">reshapr secret list</code> via
	<code class="text-xs">GET /api/v1/secrets/refs</code> — see
	<a
		href="https://github.com/reshaprio/reshapr/blob/main/cli/src/commands/secret.ts"
		target="_blank"
		rel="noreferrer"
		class="text-primary hover:underline"
	>
		cli/src/commands/secret.ts
	</a>.
</p>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<Card.Root>
	<Card.Content class="pt-6">
		<p class="text-muted-foreground mb-4 text-sm">
			{rows.length} secret{rows.length === 1 ? '' : 's'}
		</p>
		{#if rows.length === 0 && !error}
			<p class="text-muted-foreground text-sm">No secrets.</p>
		{:else}
			<div class="rounded-lg border">
				<Table.Root>
					<Table.Header>
						<Table.Row>
							<Table.Head>ID</Table.Head>
							<Table.Head>Organization</Table.Head>
							<Table.Head>Name</Table.Head>
							<Table.Head>Type</Table.Head>
							<Table.Head>Description</Table.Head>
						</Table.Row>
					</Table.Header>
					<Table.Body>
						{#each rows as row (row.id ?? `${row.name}-${row.organizationId}`)}
							<Table.Row>
								<Table.Cell><code class="text-xs">{row.id ?? '—'}</code></Table.Cell>
								<Table.Cell><code class="text-xs">{row.organizationId ?? '—'}</code></Table.Cell>
								<Table.Cell>{row.name ?? '—'}</Table.Cell>
								<Table.Cell><code class="text-xs">{row.type ?? '—'}</code></Table.Cell>
								<Table.Cell class="text-muted-foreground">
									{row.description?.trim() ? row.description : '—'}
								</Table.Cell>
							</Table.Row>
						{/each}
					</Table.Body>
				</Table.Root>
			</div>
		{/if}
	</Card.Content>
</Card.Root>
