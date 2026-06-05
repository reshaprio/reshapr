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
	import * as Alert from '$lib/components/ui/alert/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import * as Select from '$lib/components/ui/select/index.js';
	import * as Table from '$lib/components/ui/table/index.js';

	type TokenRow = { id: string; name: string; validUntil?: string };

	const VALIDITY = [1, 7, 30, 90] as const;

	let rows = $state<TokenRow[]>([]);
	let error = $state<string | null>(null);
	let createdToken = $state<string | null>(null);
	let validityDays = $state('30');

	async function load() {
		error = null;
		try {
			const data = (await apiClient().listApiTokens()) as TokenRow[];
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
		createdToken = null;
		const fd = new FormData(ev.target as HTMLFormElement);
		const name = String(fd.get('name') || '');
		const days = Number(validityDays || 30);
		if (!name) return;
		error = null;
		try {
			const out = (await apiClient().createApiToken({ name, validityDays: days })) as {
				token?: string;
			};
			if (out.token) createdToken = out.token;
			(ev.target as HTMLFormElement).reset();
			validityDays = '30';
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onDelete(tokenId: string) {
		if (!confirm('Revoke this token?')) return;
		error = null;
		try {
			await apiClient().deleteApiToken(tokenId);
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<PageHeader title="API tokens">
	{#snippet actions()}
		<Button variant="outline" onclick={() => void load()}>Refresh</Button>
	{/snippet}
</PageHeader>

{#if createdToken}
	<Alert.Root class="mb-4">
		<Alert.Title>Token (copy once)</Alert.Title>
		<Alert.Description>
			<code class="text-xs break-all">{createdToken}</code>
		</Alert.Description>
	</Alert.Root>
{/if}

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<Card.Root class="mb-6">
	<Card.Header>
		<Card.Title>Create</Card.Title>
	</Card.Header>
	<Card.Content>
		<form class="flex flex-wrap items-end gap-3" onsubmit={onCreate}>
			<Input name="name" placeholder="Name" class="max-w-xs" required />
			<Select.Root type="single" bind:value={validityDays}>
				<Select.Trigger class="w-[140px]">
					{validityDays} day{Number(validityDays) > 1 ? 's' : ''}
				</Select.Trigger>
				<Select.Content>
					{#each VALIDITY as d (d)}
						<Select.Item value={String(d)}>{d} day{d > 1 ? 's' : ''}</Select.Item>
					{/each}
				</Select.Content>
			</Select.Root>
			<Button type="submit">Create</Button>
		</form>
	</Card.Content>
</Card.Root>

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>Name</Table.Head>
				<Table.Head>Valid until</Table.Head>
				<Table.Head class="w-[100px]" />
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#each rows as t (t.id)}
				<Table.Row>
					<Table.Cell>{t.id}</Table.Cell>
					<Table.Cell>{t.name}</Table.Cell>
					<Table.Cell>
						{t.validUntil ? new Date(t.validUntil).toLocaleString() : '—'}
					</Table.Cell>
					<Table.Cell>
						<Button variant="destructive" size="sm" onclick={() => void onDelete(t.id)}>
							Revoke
						</Button>
					</Table.Cell>
				</Table.Row>
			{/each}
		</Table.Body>
	</Table.Root>
</div>
