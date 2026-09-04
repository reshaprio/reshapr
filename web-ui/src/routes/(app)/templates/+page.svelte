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
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Add01Icon, Delete02Icon, Edit01Icon } from '@hugeicons/core-free-icons';

	let templates = $state<any[]>([]);
	let error = $state<ApiError | null>(null);
	let deleteTarget = $state<string | null>(null);
	let confirmOpen = $state(false);

	const client = apiClient();

	async function loadTemplates() {
		try {
			templates = (await client.listConfigurationTemplates()) as any[];
		} catch (e) {
			error = e as ApiError;
		}
	}

	function openDeleteConfirm(id: string) {
		deleteTarget = id;
		confirmOpen = true;
	}

	async function handleDelete() {
		if (!deleteTarget) return;
		try {
			await client.deleteConfigurationTemplate(deleteTarget);
			deleteTarget = null;
			await loadTemplates();
		} catch (e) {
			error = e as ApiError;
		}
	}

	$effect(() => {
		loadTemplates();
	});
</script>

<svelte:head>
	<title>Configuration Templates – Reshapr</title>
</svelte:head>

<div class="flex items-center justify-between mb-6">
	<div>
		<h1 class="text-2xl font-semibold">Configuration Templates</h1>
		<p class="text-sm text-muted-foreground mt-1">
			Reusable blueprints to pre-populate new Configuration Plans.
		</p>
	</div>
	<Button onclick={() => goto('/templates/new')}>
		<HugeiconsIcon icon={Add01Icon} size={16} class="mr-2" />
		New Template
	</Button>
</div>

{#if error}
	<ApiErrorAlert {error} />
{/if}

{#if templates.length === 0}
	<Card.Root>
		<Card.Content class="py-12 text-center text-muted-foreground">
			No configuration templates yet. Create one to get started.
		</Card.Content>
	</Card.Root>
{:else}
	<div class="grid gap-4">
		{#each templates as template (template.id)}
			<Card.Root>
				<Card.Header class="flex flex-row items-start justify-between space-y-0 pb-2">
					<div>
						<Card.Title>{template.name}</Card.Title>
						{#if template.description}
							<Card.Description class="mt-1">{template.description}</Card.Description>
						{/if}
					</div>
					<div class="flex gap-2">
						<Button variant="outline" size="sm" onclick={() => goto(`/templates/${template.id}`)}>
							<HugeiconsIcon icon={Edit01Icon} size={14} class="mr-1" />
							Edit
						</Button>
						<Button
							variant="destructive"
							size="sm"
							onclick={() => openDeleteConfirm(template.id)}
						>
							<HugeiconsIcon icon={Delete02Icon} size={14} class="mr-1" />
							Delete
						</Button>
					</div>
				</Card.Header>
				<Card.Content>
					<p class="text-xs text-muted-foreground">
						OAuth2: {template.oauth2Configuration ? 'Configured' : 'Not configured'}
					</p>
				</Card.Content>
			</Card.Root>
		{/each}
	</div>
{/if}

<ConfirmDialog
	bind:open={confirmOpen}
	title="Delete Template"
	description="Are you sure you want to delete this template? This action cannot be undone."
	confirmLabel="Delete"
	variant="destructive"
	onconfirm={handleDelete}
	oncancel={() => (confirmOpen = false)}
/>
