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
	import { Badge } from '$lib/components/ui/badge/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';

	type Service = {
		id: string;
		name: string;
		version: string;
		type: string;
		createdOn: string;
	};

	let services = $state<Service[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);

	function pickType(raw: unknown): string {
		if (raw == null) return '—';
		if (typeof raw === 'string') return raw;
		return String(raw);
	}

	function toService(raw: unknown): Service | null {
		if (!raw || typeof raw !== 'object') return null;
		const o = raw as Record<string, unknown>;
		if (typeof o.id !== 'string') return null;
		const createdOn =
			typeof o.createdOn === 'string'
				? o.createdOn
				: typeof o.created === 'string'
					? o.created
					: '';
		return {
			id: o.id,
			name: typeof o.name === 'string' ? o.name : '—',
			version: typeof o.version === 'string' ? o.version : '—',
			type: pickType(o.type),
			createdOn
		};
	}

	function formatDate(iso: string): string {
		if (!iso) return '—';
		try {
			return new Date(iso).toLocaleDateString(undefined, {
				year: 'numeric',
				month: 'short',
				day: 'numeric'
			});
		} catch {
			return iso;
		}
	}

	function typeBadgeStyle(type: string): string {
		switch (type?.toUpperCase()) {
			case 'REST':
				return 'background-color: #6BBD4F; color: white;';
			case 'GRAPHQL':
				return 'background-color: #E10098; color: white;';
			case 'GRPC':
				return 'background-color: #c0a16b; color: white;';
			default:
				return '';
		}
	}

	async function load() {
		loading = true;
		error = null;
		try {
			const data = (await apiClient().listServices()) as unknown[];
			const list = Array.isArray(data) ? data : [];
			services = list.map(toService).filter((s): s is Service => s != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			services = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void load();
	});
</script>

<svelte:head>
	<title>Services — reShapr</title>
</svelte:head>

<PageHeader title="Services">
	{#snippet actions()}
		<Button variant="outline" disabled={loading} onclick={() => void load()}>Refresh</Button>
	{/snippet}
</PageHeader>

<p class="text-muted-foreground mb-6 text-sm">
	API services registered in your organization.
</p>

{#if loading}
	<div class="flex justify-center py-12">
		<div
			class="border-primary h-8 w-8 animate-spin rounded-full border-2 border-t-transparent"
		></div>
	</div>
{:else if error}
	<ApiErrorAlert message={error} />
	<div class="mt-4 text-center">
		<Button variant="outline" onclick={() => void load()}>Retry</Button>
	</div>
{:else if services.length === 0}
	<Card.Root>
		<Card.Content class="py-12 text-center">
			<p class="text-muted-foreground">No services found. Import a service to get started.</p>
		</Card.Content>
	</Card.Root>
{:else}
	<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
		{#each services as service (service.id)}
			<a href="/services/{service.id}" class="block">
				<Card.Root
					class="flex h-full flex-col transition-colors hover:border-primary/50 hover:bg-accent/50"
				>
					<Card.Header class="flex-1">
						<div class="flex min-w-0 items-start justify-between gap-2">
							<Card.Title class="min-w-0 flex-1 text-base leading-snug break-all">
								{service.name}
							</Card.Title>
							<Badge class="shrink-0" style={typeBadgeStyle(service.type)}>
								{service.type}
							</Badge>
						</div>
						<Card.Description class="mt-1">
							Version: <b>{service.version}</b>
						</Card.Description>
					</Card.Header>
					<Card.Content class="pt-0">
						<div class="text-muted-foreground space-y-1 text-xs">
							<p>Created {formatDate(service.createdOn)}</p>
							<p class="truncate">
								<code class="bg-muted rounded px-1 py-0.5 font-mono">{service.id}</code>
							</p>
						</div>
					</Card.Content>
				</Card.Root>
			</a>
		{/each}
	</div>
{/if}
