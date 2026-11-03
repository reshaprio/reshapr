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
	import { ApiError } from '#lib/api/client.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import PageHeader from '#lib/components/PageHeader.svelte';
	import { QuickStartWizard } from '#lib/components/artifacts/index.js';
	import { loadDashboardStats, type DashboardStats, type QuotaGauge } from '#lib/dashboardStats.js';
	import { auth } from '#lib/stores/auth.svelte.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { Badge } from '#lib/components/ui/badge/index.js';
	import * as Card from '#lib/components/ui/card/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		ApiIcon,
		ApiGatewayIcon,
		McpServerIcon,
		RefreshIcon,
		AiMagicIcon,
		TagsIcon,
		ArrowRight01Icon,
		CheckmarkCircle02Icon,
		DashboardSpeed02Icon,
		Activity01Icon
	} from '@hugeicons/core-free-icons';

	let stats = $state<DashboardStats | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(true);
	let quickStartOpen = $state(false);

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

	/** True while the user has nothing set up yet — drives the onboarding banner. */
	const isNewcomer = $derived(!loading && !error && (stats?.serviceCount ?? 0) === 0);

	type GaugeTone = { bar: string; text: string; label: string; variant: 'secondary' | 'default' | 'destructive' };

	function gaugeTone(g: QuotaGauge): GaugeTone {
		const pct = g.limit > 0 ? (g.used / g.limit) * 100 : 0;
		if (pct >= 90) return { bar: 'bg-red-500', text: 'text-red-600 dark:text-red-400', label: 'Almost full', variant: 'destructive' };
		if (pct >= 70) return { bar: 'bg-amber-500', text: 'text-amber-600 dark:text-amber-400', label: 'Filling up', variant: 'secondary' };
		return { bar: 'bg-emerald-500', text: 'text-emerald-600 dark:text-emerald-400', label: 'Healthy', variant: 'secondary' };
	}

	function gaugePct(g: QuotaGauge): number {
		return g.limit > 0 ? Math.min(100, Math.round((g.used / g.limit) * 100)) : 0;
	}

	const onboardingSteps = [
		{
			label: 'Import an API',
			description: 'Add an OpenAPI, GraphQL or gRPC definition as a service.',
			href: '/services',
			icon: ApiIcon
		},
		{
			label: 'Publish an MCP Server',
			description: 'Shape and expose your API as LLM-friendly MCP tools.',
			href: '/expositions',
			icon: McpServerIcon
		},
		{
			label: 'Connect a Gateway',
			description: 'Route MCP traffic to your backends through a gateway group.',
			href: '/gateway-groups',
			icon: TagsIcon
		}
	] as const;
</script>

<svelte:head>
	<title>Dashboard — reShapr</title>
</svelte:head>

<PageHeader title="Dashboard">
	{#snippet subtitle()}
		Welcome back, <strong>{auth.user?.username}</strong>. You're working in the
		<strong>{auth.user?.org}</strong> organization.
	{/snippet}
	{#snippet actions()}
		<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" disabled={loading} onclick={() => void load()}>
			<HugeiconsIcon icon={RefreshIcon} size={16} />
		</Button>
		<Button onclick={() => (quickStartOpen = true)}>
			<HugeiconsIcon icon={AiMagicIcon} size={16} />
			Quick Start
		</Button>
	{/snippet}
</PageHeader>

<QuickStartWizard bind:open={quickStartOpen} />

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<!-- Onboarding banner: only shown to users who haven't imported anything yet. -->
{#if isNewcomer}
	<Card.Root class="mb-6 border-primary/30 bg-linear-to-br from-primary/5 to-transparent">
		<Card.Header>
			<div class="flex items-center gap-2">
				<span class="flex size-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
					<HugeiconsIcon icon={AiMagicIcon} size={18} />
				</span>
				<Card.Title class="text-lg">Let's get you started</Card.Title>
			</div>
			<Card.Description>
				Turn any REST, GraphQL or gRPC API into LLM-friendly MCP tools in three quick steps.
			</Card.Description>
		</Card.Header>
		<Card.Content>
			<ol class="grid gap-3 sm:grid-cols-3">
				{#each onboardingSteps as step, i}
					<li>
						<a
							href={step.href}
							class="group flex h-full flex-col gap-2 rounded-lg border bg-card p-4 transition-colors hover:border-primary/50 hover:bg-accent/40"
						>
							<div class="flex items-center gap-2">
								<span class="flex size-7 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary">
									{i + 1}
								</span>
								<HugeiconsIcon icon={step.icon} class="text-muted-foreground size-5" />
							</div>
							<p class="font-medium">{step.label}</p>
							<p class="text-muted-foreground text-xs">{step.description}</p>
							<span class="text-primary mt-auto inline-flex items-center gap-1 text-xs font-medium">
								Go <HugeiconsIcon icon={ArrowRight01Icon} size={14} class="transition-transform group-hover:translate-x-0.5" />
							</span>
						</a>
					</li>
				{/each}
			</ol>
			<div class="mt-4">
				<Button onclick={() => (quickStartOpen = true)}>
					<HugeiconsIcon icon={AiMagicIcon} size={16} />
					Launch the Quick Start wizard
				</Button>
			</div>
		</Card.Content>
	</Card.Root>
{/if}

<!-- Key metrics -->
<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
	<Card.Root class="transition-colors hover:border-primary/40">
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Services</Card.Title>
			<span class="flex size-9 items-center justify-center rounded-lg bg-blue-500/10 text-blue-600 dark:text-blue-400">
				<HugeiconsIcon icon={ApiIcon} size={18} />
			</span>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.serviceCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Imported APIs</p>
			<a href="/services" class="text-primary mt-2 inline-flex items-center gap-1 text-xs font-medium hover:underline">
				View services <HugeiconsIcon icon={ArrowRight01Icon} size={12} />
			</a>
		</Card.Content>
	</Card.Root>

	<Card.Root class="transition-colors hover:border-primary/40">
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">MCP Servers</Card.Title>
			<span class="flex size-9 items-center justify-center rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-400">
				<HugeiconsIcon icon={McpServerIcon} size={18} />
			</span>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.expositionCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Published expositions</p>
			<a href="/expositions" class="text-primary mt-2 inline-flex items-center gap-1 text-xs font-medium hover:underline">
				View MCP Servers <HugeiconsIcon icon={ArrowRight01Icon} size={12} />
			</a>
		</Card.Content>
	</Card.Root>

	<Card.Root class="transition-colors hover:border-primary/40">
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Gateways</Card.Title>
			<span class="flex size-9 items-center justify-center rounded-lg bg-teal-500/10 text-teal-600 dark:text-teal-400">
				<HugeiconsIcon icon={ApiGatewayIcon} size={18} />
			</span>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.gatewayRegisteredCount)}</p>
			<p class="text-muted-foreground mt-1 flex items-center gap-1.5 text-xs">
				<HugeiconsIcon icon={Activity01Icon} class="text-emerald-500 size-3.5" />
				{fmt(stats?.gatewayHealthyCount)} healthy
			</p>
			<a href="/gateways" class="text-primary mt-2 inline-flex items-center gap-1 text-xs font-medium hover:underline">
				View gateways <HugeiconsIcon icon={ArrowRight01Icon} size={12} />
			</a>
		</Card.Content>
	</Card.Root>

	<Card.Root class="transition-colors hover:border-primary/40">
		<Card.Header class="flex flex-row items-center justify-between pb-2">
			<Card.Title class="text-sm font-medium">Gateway groups</Card.Title>
			<span class="flex size-9 items-center justify-center rounded-lg bg-amber-500/10 text-amber-600 dark:text-amber-400">
				<HugeiconsIcon icon={TagsIcon} size={18} />
			</span>
		</Card.Header>
		<Card.Content>
			<p class="text-3xl font-bold tracking-tight">{fmt(stats?.gatewayGroupsCount)}</p>
			<p class="text-muted-foreground mt-1 text-xs">Routing targets</p>
			<a href="/gateway-groups" class="text-primary mt-2 inline-flex items-center gap-1 text-xs font-medium hover:underline">
				View groups <HugeiconsIcon icon={ArrowRight01Icon} size={12} />
			</a>
		</Card.Content>
	</Card.Root>
</div>

<!-- Quota gauges -->
<Card.Root class="mt-6">
	<Card.Header class="flex flex-row items-center justify-between pb-2">
		<div class="flex items-center gap-2">
			<span class="flex size-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
				<HugeiconsIcon icon={DashboardSpeed02Icon} size={18} />
			</span>
			<div>
				<Card.Title class="text-sm font-medium">Usage &amp; quotas</Card.Title>
				<Card.Description class="text-xs">How much of your plan you've used</Card.Description>
			</div>
		</div>
	</Card.Header>
	<Card.Content>
		{#if loading}
			<p class="text-muted-foreground text-sm">Loading quotas…</p>
		{:else if !stats || stats.quotaGauges.length === 0}
			<p class="text-muted-foreground text-sm">No quota information available for this organization.</p>
		{:else}
			<div class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
				{#each stats.quotaGauges as g (g.metric)}
					{@const tone = gaugeTone(g)}
					{@const pct = gaugePct(g)}
					<div class="rounded-lg border p-4">
						<div class="mb-2 flex items-center justify-between gap-2">
							<span class="text-sm font-medium">{g.label}</span>
							<Badge variant={tone.variant} class={g.limit > 0 && g.used / g.limit >= 0.9 ? '' : 'font-normal'}>
								{tone.label}
							</Badge>
						</div>
						<div class="flex items-baseline gap-1">
							<span class="text-2xl font-bold tracking-tight {tone.text}">{g.used}</span>
							<span class="text-muted-foreground text-sm">/ {g.limit}</span>
						</div>
						<!-- Gauge -->
						<div class="bg-secondary mt-2 h-2.5 w-full overflow-hidden rounded-full">
							<div class="h-full rounded-full transition-all {tone.bar}" style="width: {pct}%"></div>
						</div>
						<p class="text-muted-foreground mt-1.5 text-xs">
							{g.remaining} remaining · {pct}% used
						</p>
					</div>
				{/each}
			</div>
			<p class="text-muted-foreground mt-4 flex items-center gap-1.5 text-xs">
				<HugeiconsIcon icon={CheckmarkCircle02Icon} size={14} class="text-emerald-500" />
				Quotas reset according to your organization's plan.
			</p>
		{/if}
	</Card.Content>
</Card.Root>

