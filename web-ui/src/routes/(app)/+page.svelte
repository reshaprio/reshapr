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
	import { loadDashboardStats, type DashboardStats } from '$lib/dashboardStats.js';
	import { auth } from '$lib/stores/auth.svelte.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import * as Collapsible from '$lib/components/ui/collapsible/index.js';
	import Activity from '@lucide/svelte/icons/activity';
	import Layers from '@lucide/svelte/icons/layers';
	import Network from '@lucide/svelte/icons/network';
	import Server from '@lucide/svelte/icons/server';

	let stats = $state<DashboardStats | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(true);
	let gatewayDetailOpen = $state(false);

	const gatewaySourceLabel: Record<
		NonNullable<DashboardStats['gatewayRegisteredDetail']>['source'],
		string
	> = {
		quota_only: 'Quota gateway.count (used > active expositions)',
		active_expositions_only: 'Active expositions only',
		max_quota_and_active: 'Max(quota, active expositions)'
	};

	async function load() {
		loading = true;
		error = null;
		try {
			stats = await loadDashboardStats();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			stats = null;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (auth.isAuthenticated) void load();
	});

	function fmt(n: number | null | undefined): string {
		if (loading) return '…';
		if (n == null) return '—';
		return String(n);
	}
</script>

<svelte:head>
	<title>Dashboard — reShapr</title>
</svelte:head>

<PageHeader title="Dashboard">
	{#snippet actions()}
		<Button variant="outline" disabled={loading} onclick={() => void load()}>Refresh</Button>
	{/snippet}
</PageHeader>

<p class="text-muted-foreground mb-4 text-sm">
	Summary for <strong>your organization</strong> ({auth.currentOrg}), using v1 APIs exposed by the control plane.
</p>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
	<!-- Platform user/org counts: no v1 API — see docs/issue-admin-api-platform-dashboard.md
	<Card.Root class="opacity-90">
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Users (platform)</Card.Title>
			<Users class="text-muted-foreground size-4" />
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.userCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Not available on v1 API</p>
		</Card.Content>
	</Card.Root>

	<Card.Root class="opacity-90">
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Organizations (platform)</Card.Title>
			<Building2 class="text-muted-foreground size-4" />
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.organizationCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">
				Current org: <code class="text-xs">{stats?.organizationId ?? '…'}</code>
			</p>
		</Card.Content>
	</Card.Root>
	-->

	<Card.Root>
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Services</Card.Title>
			<Server class="text-muted-foreground size-4" />
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.serviceCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Registered (current organization)</p>
			<a href="/services" class="text-primary mt-2 inline-block text-xs font-medium hover:underline">
				View services
			</a>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Gateways registered</Card.Title>
			<Network class="text-muted-foreground size-4" />
		</Card.Header>
		<Card.Content class="space-y-3">
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.gatewayRegisteredCount)}</p>
			<p class="text-muted-foreground text-xs">Quota or active expositions (see breakdown)</p>
			{#if stats?.gatewayRegisteredDetail}
				{@const d = stats.gatewayRegisteredDetail}
				<Collapsible.Root bind:open={gatewayDetailOpen}>
					<Collapsible.Trigger
						class="text-primary text-xs font-medium hover:underline"
						type="button"
					>
						{gatewayDetailOpen ? 'Hide' : 'Show'} calculation breakdown
					</Collapsible.Trigger>
					<Collapsible.Content class="mt-3 space-y-3 text-xs">
						<p class="text-muted-foreground">
							<strong>Displayed source:</strong>
							{gatewaySourceLabel[d.source]}
						</p>
						{#if d.quota}
							<div class="bg-muted/50 rounded-lg border p-3">
								<p class="font-medium">Quota <code>gateway.count</code></p>
								<ul class="text-muted-foreground mt-1 list-inside list-disc space-y-0.5">
									<li>used = limit − remaining = {d.quota.limit} − {d.quota.remaining} =
										<strong class="text-foreground">{d.quota.used}</strong></li>
								</ul>
							</div>
						{:else}
							<p class="text-muted-foreground">Quota <code>gateway.count</code> not available.</p>
						{/if}
						<div class="bg-muted/50 rounded-lg border p-3">
							<p class="font-medium">
								GET <code>/api/v1/expositions/active</code> — gateways deduplicated by id/name
							</p>
							<p class="text-muted-foreground mt-1">
								{d.fromActiveExpositions.registered} unique gateway(s), {d.fromActiveExpositions.healthy}
								with FQDN (healthy)
							</p>
							{#if d.fromActiveExpositions.gateways.length === 0}
								<p class="text-muted-foreground mt-2">No gateways on active expositions.</p>
							{:else}
								<ul class="mt-2 max-h-48 space-y-2 overflow-y-auto">
									{#each d.fromActiveExpositions.gateways as gw (gw.key)}
										<li class="rounded border bg-background px-2 py-1.5">
											<span class="font-mono text-foreground">{gw.key}</span>
											{#if gw.name && gw.name !== gw.key}
												<span class="text-muted-foreground"> — {gw.name}</span>
											{/if}
											<span class="text-muted-foreground">
												· FQDN: {gw.hasFqdn ? 'yes' : 'no'}
											</span>
											<br />
											<span class="text-muted-foreground">
												expositions: {gw.onActiveExpositions.join(', ')}
											</span>
										</li>
									{/each}
								</ul>
							{/if}
						</div>
						<p class="text-muted-foreground">
							Card value = {#if d.quota}
								max({d.quota.used}, {d.fromActiveExpositions.registered}) = <strong
									class="text-foreground">{d.displayedCount}</strong
								>
							{:else}
								{d.fromActiveExpositions.registered}
							{/if}
						</p>
					</Collapsible.Content>
				</Collapsible.Root>
			{/if}
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Gateways healthy</Card.Title>
			<Activity class="text-primary size-4" />
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight text-primary">{fmt(stats?.gatewayHealthyCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Active expositions + FQDN</p>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Gateway groups</Card.Title>
			<Layers class="text-muted-foreground size-4" />
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.gatewayGroupsCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Via quotas (used)</p>
		</Card.Content>
	</Card.Root>

	<Card.Root>
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Expositions</Card.Title>
			<Layers class="text-muted-foreground size-4" />
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.expositionCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Via quotas (used)</p>
		</Card.Content>
	</Card.Root>
</div>

<div class="mt-8 flex flex-wrap gap-2">
	<Button variant="outline" href="/services">Services</Button>
	<Button variant="outline" href="/expositions">Expositions</Button>
	<Button variant="outline" href="/gateway-groups">Gateway groups</Button>
	<Button variant="outline" href="/quotas">Quotas</Button>
	<Button variant="outline" href="/artifacts">Artifacts</Button>
</div>
