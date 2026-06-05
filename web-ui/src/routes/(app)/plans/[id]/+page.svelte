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
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Alert from '$lib/components/ui/alert/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { formatOperationsList, parseOperationsList } from '$lib/operationsList.js';

	const id = $derived(page.params.id);

	let raw = $state('');
	let includedOperationsText = $state('');
	let excludedOperationsText = $state('');
	let error = $state<string | null>(null);
	let apiKeyShown = $state<string | null>(null);
	let loading = $state(true);

	async function load() {
		if (!id) return;
		error = null;
		try {
			const p = await apiClient().getConfigurationPlan(id);
			raw = JSON.stringify(p, null, 2);
			syncOperationsFromRaw();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		loading = true;
		void load();
	});

	function syncOperationsFromRaw() {
		try {
			const p = JSON.parse(raw) as Record<string, unknown>;
			includedOperationsText = formatOperationsList(p.includedOperations);
			excludedOperationsText = formatOperationsList(p.excludedOperations);
		} catch {
			/* raw not valid JSON yet */
		}
	}

	function applyOperationsToDocument() {
		error = null;
		try {
			const p = JSON.parse(raw) as Record<string, unknown>;
			const includedOperations = parseOperationsList(includedOperationsText);
			const excludedOperations = parseOperationsList(excludedOperationsText);
			if (includedOperations.length) p.includedOperations = includedOperations;
			else delete p.includedOperations;
			if (excludedOperations.length) p.excludedOperations = excludedOperations;
			else delete p.excludedOperations;
			raw = JSON.stringify(p, null, 2);
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		}
	}

	async function onSave(ev: SubmitEvent) {
		ev.preventDefault();
		if (!id) return;
		error = null;
		applyOperationsToDocument();
		if (error) return;
		try {
			const parsed = JSON.parse(raw) as Record<string, unknown>;
			await apiClient().updateConfigurationPlan(id, parsed);
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onRenew() {
		if (!id) return;
		error = null;
		try {
			const out = (await apiClient().renewApiKey(id)) as { apiKey?: string };
			apiKeyShown = out.apiKey ?? '(see server response)';
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onDelete() {
		if (!id || !confirm('Delete this plan?')) return;
		try {
			await apiClient().deleteConfigurationPlan(id);
			goto('/plans');
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<p class="mb-4">
	<a href="/plans" class="text-primary text-sm hover:underline">← Plans</a>
</p>

<div class="mb-6 flex flex-wrap items-center justify-between gap-4">
	<h2 class="text-xl font-semibold tracking-tight">Plan {id}</h2>
	<div class="flex flex-wrap gap-2">
		<Button variant="outline" onclick={() => void onRenew()}>Renew API key</Button>
		<Button variant="destructive" onclick={() => void onDelete()}>Delete</Button>
	</div>
	</div>

{#if apiKeyShown}
	<Alert.Root class="mb-4">
		<Alert.Title>New API key</Alert.Title>
		<Alert.Description>
			<code class="text-xs break-all">{apiKeyShown}</code>
		</Alert.Description>
	</Alert.Root>
{/if}

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<Card.Root class="mb-6 max-w-2xl">
	<Card.Header>
		<Card.Title class="text-base">Operations filter</Card.Title>
		<Card.Description>
			Equivalent to <code class="text-xs">--io</code> / <code class="text-xs">--eo</code> on
			<code class="text-xs">reshapr config create</code>. Merged into the JSON below on save.
		</Card.Description>
	</Card.Header>
	<Card.Content class="space-y-4">
		<div class="space-y-2">
			<Label for="includedOps">Included operations</Label>
			<Textarea
				id="includedOps"
				bind:value={includedOperationsText}
				rows={4}
				class="font-mono text-xs"
				disabled={loading}
				placeholder={'POST /tests/{testId}/start\nGET /masters'}
			/>
		</div>
		<div class="space-y-2">
			<Label for="excludedOps">Excluded operations (optional)</Label>
			<Textarea
				id="excludedOps"
				bind:value={excludedOperationsText}
				rows={3}
				class="font-mono text-xs"
				disabled={loading}
			/>
		</div>
		<Button type="button" variant="outline" disabled={loading} onclick={() => applyOperationsToDocument()}>
			Preview in JSON
		</Button>
	</Card.Content>
</Card.Root>

<form class="space-y-4" onsubmit={onSave}>
	<p class="text-muted-foreground text-sm">
		Full JSON edit (PUT). Keep <code class="text-xs">id</code> and
		<code class="text-xs">organizationId</code> from the loaded document.
	</p>
	<Textarea class="font-mono text-xs" rows={22} bind:value={raw} disabled={loading} />
	<Button type="submit">Save</Button>
</form>
