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
	import { quotaEntry } from '$lib/dashboardStatsCompute.js';
	import { avatarColor } from '$lib/avatarColor.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import OrganizationBadge from '$lib/components/OrganizationBadge.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import QuotaGauge from '$lib/components/QuotaGauge.svelte';
	import { auth } from '$lib/stores/auth.svelte.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import { Tabs, TabsContent, TabsList, TabsTrigger } from '$lib/components/ui/tabs/index.js';
	import {
		Tooltip,
		TooltipContent,
		TooltipProvider,
		TooltipTrigger
	} from '$lib/components/ui/tooltip/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		ApiGatewayIcon,
		Key01Icon,
		PulseIcon,
		TagsIcon,
		RefreshIcon,
		Search01Icon,
		ArrowRight01Icon
	} from '@hugeicons/core-free-icons';
	import ApiTokensTab from './ApiTokensTab.svelte';

	const QUOTA_METRIC = 'gateway.count';

	type GatewayGroupRef = {
		id?: string;
		name?: string;
		labels?: Record<string, string>;
	};

	type Gateway = {
		id?: string;
		organizationId?: string;
		name?: string;
		version?: string;
		startedAt?: string;
		lastHeartbeat?: string;
		fqdns?: string[];
		labels?: Record<string, string>;
		gatewayGroups?: GatewayGroupRef[];
	};

	type QuotaInfo = { used: number; limit: number; remaining: number } | null;

	// Health classification derived from the gateway's last heartbeat age:
	//   < 2 min  → healthy (primary/teal)
	//   2–4 min  → degraded (amber)
	//   > 4 min  → unhealthy (destructive/red)
	// Colors are aligned with the reShapr palette (see QuotaGauge severity styles).
	type GatewayHealth = { label: string; classes: string };

	function gatewayHealth(lastHeartbeat: string | undefined): GatewayHealth {
		if (!lastHeartbeat) {
			return { label: 'Unknown', classes: 'text-muted-foreground' };
		}
		const ageMinutes = (Date.now() - new Date(lastHeartbeat).getTime()) / 60000;
		if (ageMinutes < 2) {
			return { label: 'Healthy', classes: 'text-primary' };
		}
		if (ageMinutes < 4) {
			return { label: 'Degraded', classes: 'text-amber-600 dark:text-amber-400' };
		}
		return { label: 'Unhealthy', classes: 'text-destructive' };
	}

	function formatDate(value: string | undefined): string {
		return value ? new Date(value).toLocaleString() : '—';
	}

	// ── Active tab ────────────────────────────────────────────
	let activeTab = $state('gateways');

	// ── List state ────────────────────────────────────────────
	let rows = $state<Gateway[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let query = $state('');

	// ── Quota state ───────────────────────────────────────────
	let quota = $state<QuotaInfo>(null);

	// ── Row expansion state ───────────────────────────────────
	let expandedId = $state<string | null>(null);

	function rowKey(row: Gateway): string {
		return row.id ?? row.name ?? '';
	}

	function toggleExpand(row: Gateway) {
		const key = rowKey(row);
		expandedId = expandedId === key ? null : key;
	}

	// Total number of table columns, used for the expanded detail row colspan.
	// Columns: chevron, health, name, [org], fqdns, started at.
	const columnCount = $derived(auth.isAdmin ? 6 : 5);

	function labelEntries(labels: Record<string, string> | undefined): [string, string][] {
		if (!labels || typeof labels !== 'object') return [];
		return Object.entries(labels);
	}

	// ── ApiTokensTab component ref ────────────────────────────
	let apiTokensTabRef: ReturnType<typeof ApiTokensTab> | undefined = $state();

	const filtered = $derived(
		query.trim() === ''
			? rows
			: rows.filter((r) => (r.name ?? '').toLowerCase().includes(query.trim().toLowerCase()))
	);

	function asRows(data: unknown[]): Gateway[] {
		return data.filter((r): r is Gateway => r !== null && typeof r === 'object');
	}

	function fqdnList(fqdns: string[] | undefined): string[] {
		return Array.isArray(fqdns) ? fqdns : [];
	}

	async function refreshQuota() {
		try {
			quota = quotaEntry(await apiClient().getQuotas(), QUOTA_METRIC);
		} catch {
			// Non-critical: leave quota as-is so the UI stays usable.
		}
	}

	async function load() {
		loading = true;
		error = null;
		try {
			const [data] = await Promise.all([apiClient().listGateways(), refreshQuota()]);
			rows = asRows(Array.isArray(data) ? data : []);
		} catch (e) {
			error = formatApiError(e);
			rows = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void load();
	});
</script>

<svelte:head>
	<title>Gateways — reShapr</title>
</svelte:head>

<div class="space-y-6">
	<!-- Header -->
	<PageHeader
		title="Gateways"
		subtitle="Active gateways and the API tokens used to connect them to the control plane."
	>
		{#snippet actions()}
			{#if activeTab === 'gateways'}
				<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" disabled={loading} onclick={() => void load()}>
					<HugeiconsIcon icon={RefreshIcon} size={16} />
				</Button>
			{:else if activeTab === 'apitokens'}
				<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" onclick={() => apiTokensTabRef?.refresh()}>
					<HugeiconsIcon icon={RefreshIcon} size={16} />
				</Button>
				<Button onclick={() => apiTokensTabRef?.openCreateDrawer()}>New token</Button>
			{/if}
		{/snippet}
	</PageHeader>

	<!-- Tabs -->
	<Tabs bind:value={activeTab}>
		<TabsList
			class="mb-6 h-auto w-full justify-start gap-1 rounded-none border-b border-border bg-transparent p-0 pb-3"
		>
			<TabsTrigger
				value="gateways"
				class="flex-none rounded-lg px-3 py-2 text-sm font-normal text-muted-foreground transition-colors hover:bg-muted hover:text-foreground data-active:bg-primary/10 data-active:font-medium data-active:text-primary data-active:shadow-none"
			>
				<HugeiconsIcon icon={ApiGatewayIcon} size={16} />
				Gateways
			</TabsTrigger>
			<TabsTrigger
				value="apitokens"
				class="flex-none rounded-lg px-3 py-2 text-sm font-normal text-muted-foreground transition-colors hover:bg-muted hover:text-foreground data-active:bg-primary/10 data-active:font-medium data-active:text-primary data-active:shadow-none"
			>
				<HugeiconsIcon icon={Key01Icon} size={16} />
				API Tokens
			</TabsTrigger>
		</TabsList>

		<!-- ═══════════════════════════════════════════════════════ -->
		<!-- Gateways Tab                                           -->
		<!-- ═══════════════════════════════════════════════════════ -->
		<TabsContent value="gateways" class="pt-4">
			{#if error}
				<div class="mb-4">
					<ApiErrorAlert message={error} />
				</div>
			{/if}

			{#if quota}
				<div class="mb-4">
					<QuotaGauge
						{quota}
						label="Gateway quota"
						fullMessage="Quota reached. Stop an existing gateway before starting a new one."
					/>
				</div>
			{/if}

			<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
				<div class="flex items-baseline gap-2">
					<h3 class="text-base font-semibold">Active gateways</h3>
					{#if !loading}
						<span class="text-muted-foreground text-sm">
							{#if query.trim()}
								{filtered.length} / {rows.length}
							{:else}
								{rows.length} gateway{rows.length === 1 ? '' : 's'}
							{/if}
						</span>
					{/if}
				</div>
				{#if !loading && rows.length > 0}
					<div class="relative w-full sm:w-64">
						<HugeiconsIcon
							icon={Search01Icon}
							size={16}
							class="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2"
						/>
						<Input bind:value={query} placeholder="Filter by name…" class="pl-8" />
					</div>
				{/if}
			</div>

			{#if loading}
				<div class="text-muted-foreground rounded-lg border py-12 text-center text-sm">Loading…</div>
			{:else if rows.length === 0}
				<div
					class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
				>
					<p class="text-sm">No active gateway.</p>
					<p class="mt-1 text-xs">Gateways appear here once they start and report health.</p>
				</div>
			{:else if filtered.length === 0}
				<div
					class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
				>
					<p class="text-sm">No gateway matches “{query}”.</p>
				</div>
			{:else}
				<div class="rounded-lg border">
					<Table.Root>
						<Table.Header>
							<Table.Row>
								<Table.Head class="w-8"></Table.Head>
								<Table.Head class="w-16 text-center">Health</Table.Head>
								<Table.Head>Name</Table.Head>
								{#if auth.isAdmin}
									<Table.Head>Org</Table.Head>
								{/if}
								<Table.Head>FQDNs</Table.Head>
								<Table.Head>Started at</Table.Head>
							</Table.Row>
						</Table.Header>
						<Table.Body>
							{#each filtered as row (rowKey(row))}
								{@const health = gatewayHealth(row.lastHeartbeat)}
								{@const expanded = expandedId === rowKey(row)}
								<Table.Row class="cursor-pointer" onclick={() => toggleExpand(row)}>
									<Table.Cell class="text-muted-foreground">
										<span
											class="inline-flex transition-transform duration-200 {expanded ? 'rotate-90' : ''}"
										>
											<HugeiconsIcon icon={ArrowRight01Icon} size={16} />
										</span>
									</Table.Cell>
									<Table.Cell class="text-center">
										<TooltipProvider delayDuration={150}>
											<Tooltip>
												<TooltipTrigger>
													{#snippet child({ props })}
														<span
															{...props}
															class="inline-flex items-center justify-center {health.classes}"
														>
															<HugeiconsIcon icon={PulseIcon} size={18} />
															<span class="sr-only">{health.label}</span>
														</span>
													{/snippet}
												</TooltipTrigger>
												<TooltipContent>
													<div class="space-y-0.5 text-center">
														<p class="font-medium">{health.label}</p>
														<p>Last heartbeat: {formatDate(row.lastHeartbeat)}</p>
													</div>
												</TooltipContent>
											</Tooltip>
										</TooltipProvider>
									</Table.Cell>
									<Table.Cell class="font-medium">{row.name ?? '—'}</Table.Cell>
									{#if auth.isAdmin}
										<Table.Cell>
											{#if row.organizationId}
												<OrganizationBadge organizationName={row.organizationId} />
											{:else}
												—
											{/if}
										</Table.Cell>
									{/if}
									<Table.Cell>
										{@const fqdns = fqdnList(row.fqdns)}
										{#if fqdns.length === 0}
											<span class="text-muted-foreground">—</span>
										{:else}
											<div class="flex flex-wrap gap-1">
												{#each fqdns as fqdn (fqdn)}
													<Badge variant="secondary" class="font-mono text-xs">{fqdn}</Badge>
												{/each}
											</div>
										{/if}
									</Table.Cell>
									<Table.Cell class="text-muted-foreground text-sm whitespace-nowrap">
										{formatDate(row.startedAt)}
									</Table.Cell>
								</Table.Row>

								{#if expanded}
									{@const labels = labelEntries(row.labels)}
									{@const groups = row.gatewayGroups ?? []}
									<Table.Row class="bg-muted/30 hover:bg-muted/30">
										<Table.Cell colspan={columnCount} class="p-0">
											<div class="grid gap-6 px-6 py-4 sm:grid-cols-3">
												<!-- Version -->
												<div class="space-y-1.5">
													<p
														class="text-muted-foreground text-xs font-semibold tracking-wider uppercase"
													>
														Version
													</p>
													{#if row.version}
														<Badge variant="secondary" class="font-mono text-xs">{row.version}</Badge>
													{:else}
														<span class="text-muted-foreground text-sm">—</span>
													{/if}
												</div>

												<!-- Labels -->
												<div class="space-y-1.5">
													<p
														class="text-muted-foreground text-xs font-semibold tracking-wider uppercase"
													>
														Labels
													</p>
													{#if labels.length === 0}
														<span class="text-muted-foreground text-sm">No labels</span>
													{:else}
														<div class="flex flex-wrap gap-1">
															{#each labels as [k, v] (k)}
																<Badge
																	class="border-transparent font-mono text-xs text-white"
																	style="background-color: {avatarColor(k)};"
																>
																	{k}={v}
																</Badge>
															{/each}
														</div>
													{/if}
												</div>

												<!-- Gateway groups -->
												<div class="space-y-1.5">
													<p
														class="text-muted-foreground text-xs font-semibold tracking-wider uppercase"
													>
														Gateway groups
													</p>
													{#if groups.length === 0}
														<span class="text-muted-foreground text-sm">Not mapped to any group</span>
													{:else}
														<div class="flex flex-wrap gap-1">
															{#each groups as group (group.id ?? group.name)}
																<Badge variant="secondary" class="gap-1 text-xs">
																	<HugeiconsIcon icon={TagsIcon} size={12} />
																	{group.name ?? group.id}
																</Badge>
															{/each}
														</div>
													{/if}
												</div>
											</div>
										</Table.Cell>
									</Table.Row>
								{/if}
							{/each}
						</Table.Body>
					</Table.Root>
				</div>
			{/if}
		</TabsContent>

		<!-- ═══════════════════════════════════════════════════════ -->
		<!-- API Tokens Tab                                         -->
		<!-- ═══════════════════════════════════════════════════════ -->
		<TabsContent value="apitokens" class="pt-4">
			<ApiTokensTab bind:this={apiTokensTabRef} />
		</TabsContent>
	</Tabs>
</div>

