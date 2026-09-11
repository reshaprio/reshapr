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
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';

	const client = apiClient();

	let name = $state('');
	let description = $state('');
	// OAuth2 fields
	let authorizationServers = $state('');
	let jwksUri = $state('');
	let scopes = $state('');
	let useOAuth2 = $state(false);

	let error = $state<ApiError | null>(null);
	let saving = $state(false);

	async function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = null;

		const body: any = { name, description: description || null };

		if (useOAuth2) {
			body.oauth2Configuration = {
				authorizationServers: authorizationServers
					.split(',')
					.map((s) => s.trim())
					.filter(Boolean),
				jwksUri: jwksUri || null,
				scopes: scopes
					? scopes
							.split(',')
							.map((s) => s.trim())
							.filter(Boolean)
					: null
			};
		}

		try {
			await client.createConfigurationTemplate(body);
			goto('/templates');
		} catch (e) {
			error = e as ApiError;
			saving = false;
		}
	}
</script>

<svelte:head>
	<title>New Configuration Template – Reshapr</title>
</svelte:head>

<div class="mb-4">
	<a href="/templates" class="text-primary text-sm hover:underline">← Configuration Templates</a>
</div>

<h1 class="text-2xl font-semibold mb-6">New Configuration Template</h1>

{#if error}
	<ApiErrorAlert {error} />
{/if}

<form onsubmit={handleSubmit} class="space-y-6 max-w-xl">
	<Card.Root>
		<Card.Header>
			<Card.Title>Basic Info</Card.Title>
		</Card.Header>
		<Card.Content class="space-y-4">
			<div class="space-y-1">
				<Label for="name">Name *</Label>
				<Input id="name" bind:value={name} required placeholder="e.g. corporate-oauth2" />
			</div>
			<div class="space-y-1">
				<Label for="description">Description</Label>
				<Textarea
					id="description"
					bind:value={description}
					placeholder="Optional description for this template"
				/>
			</div>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="flex flex-row items-center justify-between space-y-0">
			<Card.Title>OAuth2 Configuration</Card.Title>
			<label class="flex items-center gap-2 text-sm cursor-pointer">
				<input type="checkbox" bind:checked={useOAuth2} class="rounded" />
				Enable
			</label>
		</Card.Header>
		{#if useOAuth2}
			<Card.Content class="space-y-4">
				<div class="space-y-1">
					<Label for="authServers">Authorization Servers (comma-separated URLs)</Label>
					<Input
						id="authServers"
						bind:value={authorizationServers}
						placeholder="https://idp.example.com"
					/>
				</div>
				<div class="space-y-1">
					<Label for="jwksUri">JWKS URI</Label>
					<Input
						id="jwksUri"
						bind:value={jwksUri}
						placeholder="https://idp.example.com/.well-known/jwks.json"
					/>
				</div>
				<div class="space-y-1">
					<Label for="scopes">Scopes (comma-separated, optional)</Label>
					<Input id="scopes" bind:value={scopes} placeholder="read write" />
				</div>
			</Card.Content>
		{/if}
	</Card.Root>

	<div class="flex gap-2">
		<Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Create Template'}</Button>
		<Button type="button" variant="outline" onclick={() => goto('/templates')}>Cancel</Button>
	</div>
</form>
