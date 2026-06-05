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
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import { parseServiceRecord } from '$lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { cn } from '$lib/utils.js';

	let { children } = $props();

	const serviceId = $derived(page.params.id ?? '');

	const subNav: { href: (id: string) => string; label: string; exact?: boolean }[] = [
		{ href: (id) => `/services/${id}`, label: 'Overview', exact: true },
		{ href: (id) => `/services/${id}/artifacts`, label: 'Artifacts' },
		{ href: (id) => `/services/${id}/plans`, label: 'Configuration plans' },
		{ href: (id) => `/services/${id}/expositions`, label: 'Expositions' },
		{ href: (id) => `/services/${id}/mcp-custom-tools`, label: 'MCP custom tools' },
		{ href: (id) => `/services/${id}/mcp-prompts`, label: 'MCP prompts' }
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

	async function onDelete() {
		if (!serviceId || !confirm('Delete this service?')) return;
		try {
			await apiClient().deleteService(serviceId);
			goto('/services');
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<p class="mb-4">
	<a href="/services" class="text-primary text-sm hover:underline">← Services</a>
</p>

<div class="mb-4 flex flex-wrap items-start justify-between gap-4">
	<div class="min-w-0">
		{#if loading}
			<h2 class="text-xl font-semibold tracking-tight">Service …</h2>
		{:else if service}
			<h2 class="text-xl font-semibold tracking-tight">
				{service.name}
				<span class="text-muted-foreground font-normal">:{service.version}</span>
			</h2>
			<p class="text-muted-foreground mt-1 text-sm">
				<code class="text-xs">{service.id}</code>
				{#if service.organizationId}
					· org <code class="text-xs">{service.organizationId}</code>
				{/if}
				· {service.type}
			</p>
		{:else}
			<h2 class="text-xl font-semibold tracking-tight">Service {serviceId}</h2>
		{/if}
	</div>
	<Button variant="destructive" size="sm" disabled={loading} onclick={() => void onDelete()}>
		Delete service
	</Button>
</div>

{#if error}
	<div class="mb-4">
		<ApiErrorAlert message={error} />
	</div>
{/if}

<nav class="border-border mb-6 flex flex-wrap gap-1 border-b pb-3">
	{#each subNav as item (item.label)}
		{@const href = item.href(serviceId)}
		<a href={href} class={subNavClass(href, item.exact ?? false)}>{item.label}</a>
	{/each}
</nav>

{@render children()}
