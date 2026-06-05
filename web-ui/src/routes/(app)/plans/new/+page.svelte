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
	import { goto } from '$app/navigation';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import * as Alert from '$lib/components/ui/alert/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { Checkbox } from '$lib/components/ui/checkbox/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { parseOperationsList } from '$lib/operationsList.js';

	let error = $state<string | null>(null);
	let apiKeyShown = $state<string | null>(null);
	let createdId = $state<string | null>(null);
	let genKey = $state(false);
	let includedOperationsText = $state('');
	let excludedOperationsText = $state('');

	async function onSubmit(ev: SubmitEvent) {
		ev.preventDefault();
		error = null;
		apiKeyShown = null;
		createdId = null;
		const fd = new FormData(ev.target as HTMLFormElement);
		const name = String(fd.get('name') || '');
		const serviceId = String(fd.get('serviceId') || '');
		const backendEndpoint = String(fd.get('backendEndpoint') || '');
		const description = String(fd.get('description') || '') || undefined;
		const backendSecretId = String(fd.get('backendSecretId') || '') || undefined;
		if (!name || !serviceId || !backendEndpoint) {
			error = 'name, serviceId, and backendEndpoint are required.';
			return;
		}
		try {
			const includedOperations = parseOperationsList(includedOperationsText);
			const excludedOperations = parseOperationsList(excludedOperationsText);

			const body: Record<string, unknown> = {
				name,
				serviceId,
				backendEndpoint,
				description,
				backendSecretId
			};
			if (includedOperations.length) body.includedOperations = includedOperations;
			if (excludedOperations.length) body.excludedOperations = excludedOperations;
			if (genKey) body.apiKey = 'generate-me';

			const out = (await apiClient().createConfigurationPlan(body)) as {
				id: string;
				apiKey?: string;
			};
			createdId = out.id;
			if (out.apiKey) apiKeyShown = out.apiKey;
			else goto(`/plans/${out.id}`);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<PageHeader title="New plan" />

<Alert.Root class="mb-4">
	<Alert.Title>Configuration plan</Alert.Title>
	<Alert.Description>
		Same as <code class="text-xs">reshapr config create</code>. Use <strong>Included operations</strong>
		(<code class="text-xs">--io</code>) to expose only selected API routes. Attach a
		<code class="text-xs">CustomTools</code> YAML on <a href="/artifacts" class="text-primary hover:underline">Artifacts</a>
		for MCP custom tools (step 2).
	</Alert.Description>
</Alert.Root>

{#if apiKeyShown}
	<Alert.Root class="mb-4">
		<Alert.Title>API key (copy now)</Alert.Title>
		<Alert.Description>
			<code class="text-xs break-all">{apiKeyShown}</code>
			<p class="mt-2">
				<a href={createdId ? `/plans/${createdId}` : '/plans'} class="text-primary hover:underline">
					Open created plan
				</a>
			</p>
		</Alert.Description>
	</Alert.Root>
{/if}

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<Card.Root class="max-w-2xl">
	<Card.Content class="pt-6">
		<form class="space-y-4" onsubmit={onSubmit}>
			<div class="space-y-2">
				<Label for="name">Name</Label>
				<Input id="name" name="name" placeholder="blazemeter-tests-operations" required />
			</div>
			<div class="space-y-2">
				<Label for="serviceId">Service ID</Label>
				<Input id="serviceId" name="serviceId" placeholder="from Services list" required />
			</div>
			<div class="space-y-2">
				<Label for="backendEndpoint">Backend endpoint URL</Label>
				<Input
					id="backendEndpoint"
					name="backendEndpoint"
					class="w-full"
					placeholder="https://a.blazemeter.com/api/v4"
					required
				/>
			</div>
			<div class="space-y-2">
				<Label for="description">Description</Label>
				<Input id="description" name="description" />
			</div>
			<div class="space-y-2">
				<Label for="backendSecretId">Backend secret ID</Label>
				<Input id="backendSecretId" name="backendSecretId" />
			</div>

			<div class="space-y-2">
				<Label for="includedOperations">Included operations (<code class="text-xs">--io</code>)</Label>
				<Textarea
					id="includedOperations"
					bind:value={includedOperationsText}
					rows={4}
					class="font-mono text-xs"
					placeholder={'POST /tests/{testId}/start\nGET /masters'}
				/>
				<p class="text-muted-foreground text-xs">
					One operation per line, or a JSON array. Leave empty to include all service operations (after
					custom-tools filtering).
				</p>
			</div>

			<div class="space-y-2">
				<Label for="excludedOperations">Excluded operations (<code class="text-xs">--eo</code>, optional)</Label>
				<Textarea
					id="excludedOperations"
					bind:value={excludedOperationsText}
					rows={3}
					class="font-mono text-xs"
					placeholder="Only used when included operations is empty"
				/>
			</div>

			<div class="flex items-center gap-2">
				<Checkbox id="apiKey" bind:checked={genKey} />
				<Label for="apiKey">Generate an API key</Label>
			</div>
			<Button type="submit">Create plan</Button>
		</form>
	</Card.Content>
</Card.Root>
