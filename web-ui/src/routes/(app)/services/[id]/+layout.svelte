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
	import { page } from '$app/state';
	import { setContext } from 'svelte';
	import { apiClient, ApiError } from '#lib/api/client.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import ConfirmDialog from '#lib/components/ConfirmDialog.svelte';
	import OrganizationBadge from '#lib/components/OrganizationBadge.svelte';
	import ServiceTypeBadge from '#lib/components/ServiceTypeBadge.svelte';
	import { parseServiceRecord } from '#lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '#lib/serviceContext.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { cn } from '#lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Delete02Icon } from '@hugeicons/core-free-icons';

	let { children } = $props();

	const serviceId = $derived(page.params.id ?? '');

	const subNav: { href: (id: string) => string; label: string; exact?: boolean }[] = [
		{ href: (id) => `/services/${id}`, label: 'Overview', exact: true },
		{ href: (id) => `/services/${id}/artifacts`, label: 'Artifacts' },
		{ href: (id) => `/services/${id}/plans`, label: 'Configuration plans' },
		{ href: (id) => `/services/${id}/expositions`, label: 'Expositions' }
	];

	let raw = $state<unknown>(null);
	let service = $state<ReturnType<typeof parseServiceRecord>>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	async function refresh() {
		if (!serviceId) return;
		loading = true;
		error = null;
		try {
			const data = await apiClient().getService(serviceId);
			raw = data;
			service = parseServiceRecord(data);
			if (!service) {
				error = 'Invalid service payload';
			}
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			raw = null;
			service = null;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		const id = serviceId;
		if (!id) return;
		void refresh();
	});

	const ctx: ServiceContextValue = {
		get id() {
			return serviceId;
		},
		get service() {
			return service;
		},
		get raw() {
			return raw;
		},
		get loading() {
			return loading;
		},
		get error() {
			return error;
		},
		refresh
	};

	setContext(SERVICE_CONTEXT_KEY, ctx);

	// ── Identity helpers (ID + creation date shown in the hero) ──
	function str(v: unknown): string | null {
		return typeof v === 'string' && v.trim() !== '' ? v : null;
	}

	const createdOn = $derived.by<string | null>(() => {
		const r = raw as Record<string, unknown> | null;
		return r ? (str(r.createdOn) ?? str(r.created)) : null;
	});

	function formatDate(iso: string | null): string {
		if (!iso) return '—';
		try {
			return new Date(iso).toLocaleString(undefined, {
				year: 'numeric',
				month: 'short',
				day: 'numeric',
				hour: '2-digit',
				minute: '2-digit'
			});
		} catch {
			return iso;
		}
	}

	function subNavClass(href: string, exact: boolean): string {
		const path = page.url.pathname;
		const active = exact ? path === href : path === href || path.startsWith(href + '/');
		return cn(
			'rounded-lg px-3 py-2 text-sm transition-colors',
			active
				? 'bg-primary/10 font-medium text-primary'
				: 'text-muted-foreground hover:bg-muted hover:text-foreground'
		);
	}

	let deleteOpen = $state(false);

	function onDelete() {
		if (!serviceId) return;
		deleteOpen = true;
	}

	async function confirmDeleteService() {
		if (!serviceId) return;
		await apiClient().deleteService(serviceId);
		goto('/services');
	}
</script>

<p class="mb-4">
	<a href="/services" class="text-primary text-sm hover:underline">← Services</a>
</p>

{#if error}
	<div class="mb-4">
		<ApiErrorAlert message={error} />
	</div>
{/if}

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Hero / identity                                              -->
<!-- ═══════════════════════════════════════════════════════════ -->
<div class="bg-card mb-6 flex flex-wrap items-start justify-between gap-4 rounded-xl border p-6">
	<div class="flex min-w-0 items-start gap-4">
		<div class="min-w-0">
			<div class="flex flex-wrap items-center gap-2">
				<h1 class="text-2xl font-bold tracking-tight break-all">
					{#if loading}Service …{:else}{service?.name ?? serviceId}{/if}
				</h1>
				{#if service?.type}
					<ServiceTypeBadge type={service.type} />
				{/if}
			</div>
			{#if service?.version}
				<div class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
					<span class="text-muted-foreground">Version</span>
					<span class="text-foreground font-medium break-all">{service.version}</span>
				</div>
			{/if}
			<div class="mt-2 flex flex-wrap items-center gap-2">
				<code
					class="text-muted-foreground bg-muted rounded px-1.5 py-0.5 font-mono text-xs break-all"
				>
					{serviceId}
				</code>
				<span class="text-muted-foreground text-xs">
					Created on {loading ? '…' : formatDate(createdOn)}
				</span>
				{#if service?.organizationId}
					<OrganizationBadge organizationName={service.organizationId} />
				{/if}
			</div>
		</div>
	</div>
	<div class="flex shrink-0 items-center gap-2">
		<Button variant="destructive" disabled={loading} onclick={() => void onDelete()}>
			<HugeiconsIcon icon={Delete02Icon} size={16} />
			Delete service
		</Button>
	</div>
</div>

<nav class="border-border mb-6 flex flex-wrap gap-1 border-b pb-3">
	{#each subNav as item (item.label)}
		{@const href = item.href(serviceId)}
		<a href={href} class={subNavClass(href, item.exact ?? false)}>{item.label}</a>
	{/each}
</nav>

{@render children()}

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete service"
	description={`You are about to delete the service "${service?.name ?? serviceId}". This action cannot be undone.`}
	confirmLabel="Delete"
	onConfirm={confirmDeleteService}
>
	<p class="text-muted-foreground text-sm">
		All expositions, configuration plans and artifacts attached to this service will be permanently
		removed, and any MCP endpoint it exposes will stop responding.
	</p>
</ConfirmDialog>

