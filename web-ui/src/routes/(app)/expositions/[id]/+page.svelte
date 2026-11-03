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
	import { apiClient, ApiError } from '#lib/api/client.js';
	import { parseArtifactRefList, type ArtifactRef } from '#lib/artifacts/index.js';
	import { avatarColor, avatarInitials } from '#lib/avatarColor.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import ConfirmDialog from '#lib/components/ConfirmDialog.svelte';
	import OrganizationBadge from '#lib/components/OrganizationBadge.svelte';
	import ServiceTypeBadge from '#lib/components/ServiceTypeBadge.svelte';
	import PlanOperationsView from '#lib/components/plan/PlanOperationsView.svelte';
	import PlanCapabilitiesView from '#lib/components/plan/PlanCapabilitiesView.svelte';
	import PlanBackendView from '#lib/components/plan/PlanBackendView.svelte';
	import PlanClientAuthView from '#lib/components/plan/PlanClientAuthView.svelte';
	import PlanAuditView from '#lib/components/plan/PlanAuditView.svelte';
	import { auth } from '#lib/stores/auth.svelte.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { Badge } from '#lib/components/ui/badge/index.js';
	import * as Card from '#lib/components/ui/card/index.js';
	import { cn } from '#lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		ApiGatewayIcon,
		ArrowRight01Icon,
		CloudServerIcon,
		Configuration01Icon,
		Copy01Icon,
		Delete02Icon,
		Link01Icon,
		McpServerIcon,
		TagsIcon,
		Tick02Icon,
		RefreshIcon
	} from '@hugeicons/core-free-icons';

	const id = $derived(page.params.id);

	// ── Raw API state ─────────────────────────────────────────
	let expo = $state<Record<string, unknown> | null>(null);
	let active = $state<Record<string, unknown> | null>(null);
	let artifacts = $state<ArtifactRef[]>([]);
	let backendSecret = $state<{ name: string; type: string } | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(true);

	// ── Small parsing helpers ─────────────────────────────────
	function rec(v: unknown): Record<string, unknown> | null {
		return v && typeof v === 'object' ? (v as Record<string, unknown>) : null;
	}
	function str(v: unknown): string | null {
		return typeof v === 'string' && v.trim() !== '' ? v.trim() : null;
	}
	function arr(v: unknown): unknown[] {
		return Array.isArray(v) ? v : [];
	}

	// ── Derived, typed views over the raw payloads ────────────
	const svc = $derived(rec(expo?.service));
	const plan = $derived(rec(expo?.configurationPlan));
	const gg = $derived(rec(expo?.gatewayGroup));

	const expoName = $derived(str(expo?.name));
	const isActive = $derived(active != null);

	const serviceName = $derived(str(svc?.name) ?? '');
	const serviceVersion = $derived(str(svc?.version));
	const serviceType = $derived(str(svc?.type));
	const serviceId = $derived(str(svc?.id));

	const heroTitle = $derived(expoName ?? serviceName ?? 'MCP Server');
	const heroSubtitle = $derived(
		serviceName ? `${serviceName}${serviceVersion ? ` : ${serviceVersion}` : ''}` : null
	);


	// ── Gateways / endpoint URLs (only available when active) ──
	const gateways = $derived.by<{ id: string; name: string | null; fqdns: string[] }[]>(() =>
		arr(active?.gateways)
			.map((g) => {
				const o = rec(g);
				if (!o) return null;
				return {
					id: str(o.id) ?? '',
					name: str(o.name),
					fqdns: arr(o.fqdns)
						.map(String)
						.filter((f) => f.length > 0)
				};
			})
			.filter((g): g is { id: string; name: string | null; fqdns: string[] } => g != null)
	);

	function endpointBase(fqdn: string): string {
		if (/^https?:\/\//i.test(fqdn)) return fqdn.replace(/\/+$/, '');
		const host = fqdn.split(/[:/]/, 1)[0].toLowerCase();
		const scheme = host === 'localhost' || host === '127.0.0.1' ? 'http' : 'https';
		return `${scheme}://${fqdn}`;
	}
	const encSeg = (s: string) => s.replace(/\s/g, '+');

	const endpointUrls = $derived.by<string[]>(() => {
		const idStr = str(expo?.id) ?? '';
		const org = str(expo?.organizationId) ?? '';
		const name = expoName ?? '';
		if (!idStr) return [];
		const fqdns: string[] = [];
		for (const g of gateways) for (const f of g.fqdns) if (!fqdns.includes(f)) fqdns.push(f);
		const urls: string[] = [];
		for (const fqdn of fqdns) {
			const base = endpointBase(fqdn);
			if (name && org) urls.push(`${base}/mcp/${org}/${encSeg(name)}`);
			urls.push(`${base}/mcp/${encSeg(idStr)}`);
		}
		return urls;
	});

	// ── Gateway group labels ──────────────────────────────────
	const ggLabels = $derived.by<[string, string][]>(() => {
		const l = rec(gg?.labels);
		return l ? Object.entries(l).map(([k, v]) => [k, String(v)] as [string, string]) : [];
	});

	// ── Load ──────────────────────────────────────────────────
	async function loadArtifacts(sid: string) {
		try {
			artifacts = parseArtifactRefList(await apiClient().listArtifactRefsByService(sid));
		} catch {
			artifacts = [];
		}
	}

	async function loadBackendSecret(secretId: string) {
		try {
			const refs = await apiClient().listSecretRefs();
			const match = (Array.isArray(refs) ? refs : [])
				.map(rec)
				.find((s) => s && str(s.id) === secretId);
			backendSecret = match
				? { name: str(match.name) ?? secretId, type: str(match.type) ?? '' }
				: { name: secretId, type: '' };
		} catch {
			backendSecret = { name: secretId, type: '' };
		}
	}

	async function load(expoId: string) {
		loading = true;
		error = null;
		artifacts = [];
		backendSecret = null;
		try {
			const c = apiClient();
			const detail = rec(await c.getExposition(expoId));
			expo = detail;
			active = rec(await c.getActiveExpositionOrNull(expoId));

			const p = rec(detail?.configurationPlan);
			const sid = str(rec(detail?.service)?.id);
			const secretId = str(p?.backendSecretId);
			await Promise.all([
				sid ? loadArtifacts(sid) : Promise.resolve(),
				secretId ? loadBackendSecret(secretId) : Promise.resolve()
			]);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		const expoId = id;
		if (!expoId) return;
		void load(expoId);
	});

	// ── Copy endpoint URL ─────────────────────────────────────
	let copiedUrl = $state<string | null>(null);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;
	async function copyUrl(url: string) {
		try {
			await navigator.clipboard.writeText(url);
			copiedUrl = url;
			clearTimeout(copiedTimer);
			copiedTimer = setTimeout(() => (copiedUrl = null), 1500);
		} catch {
			// Clipboard may be unavailable (insecure context); ignore.
		}
	}

	let deleteOpen = $state(false);

	function onDelete() {
		if (!id) return;
		deleteOpen = true;
	}

	async function confirmDeleteExposition() {
		if (!id) return;
		await apiClient().deleteExposition(id);
		goto('/expositions');
	}
</script>

<svelte:head>
	<title>{heroTitle} — MCP Server — reShapr</title>
</svelte:head>

<p class="mb-4">
	<a href="/expositions" class="text-primary text-sm hover:underline">← MCP Servers</a>
</p>

{#if error}
	<div class="mb-4"><ApiErrorAlert message={error} /></div>
{/if}

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Hero                                                         -->
<!-- ═══════════════════════════════════════════════════════════ -->
<div class="bg-card mb-6 flex flex-wrap items-start justify-between gap-4 rounded-xl border p-6">
	<div class="flex min-w-0 items-start gap-4">
		<span
			class="flex size-14 shrink-0 items-center justify-center rounded-xl text-lg font-semibold text-white"
			style="background-color: {avatarColor(serviceName || heroTitle)};"
			aria-hidden="true"
		>
			{avatarInitials(serviceName || heroTitle)}
		</span>
		<div class="min-w-0">
			<div class="flex flex-wrap items-center gap-2">
				<h1 class="text-2xl font-bold tracking-tight break-all">{heroTitle}</h1>
				<span
					class={cn(
						'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
						isActive
							? 'bg-primary/10 text-primary ring-primary/20'
							: 'bg-muted text-muted-foreground ring-border'
					)}
				>
					<span
						class={cn(
							'size-1.5 rounded-full',
							isActive ? 'bg-primary' : 'bg-muted-foreground/50'
						)}
					></span>
					{isActive ? 'Active' : 'Inactive'}
				</span>
			</div>
			{#if heroSubtitle}
				<div class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
					<span class="text-muted-foreground">Backed by</span>
					<span class="text-foreground font-medium break-all">{serviceName || '—'}</span>
					{#if serviceVersion}
						<span class="text-muted-foreground">version {serviceVersion}</span>
					{/if}
					{#if serviceId}
						<a
							href="/services/{serviceId}"
							class="text-primary text-xs inline-flex items-center gap-1 hover:underline"
						>
							Open service <HugeiconsIcon icon={ArrowRight01Icon} size={14} />
						</a>
					{/if}
				</div>
			{/if}
			<div class="mt-2 flex flex-wrap items-center gap-2">
				<code class="text-muted-foreground bg-muted rounded px-1.5 py-0.5 font-mono text-xs break-all">
					{id}
				</code>
				{#if serviceType}
					<ServiceTypeBadge type={serviceType} />
				{/if}
				{#if auth.isAdmin && str(expo?.organizationId)}
					<OrganizationBadge organizationName={str(expo?.organizationId) ?? ''} />
				{/if}
			</div>
		</div>
	</div>
	<div class="flex shrink-0 items-center gap-2">
		<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" onclick={() => id && void load(id)}>
			<HugeiconsIcon icon={RefreshIcon} size={16} />
		</Button>
		<Button variant="destructive" onclick={() => void onDelete()}>
			<HugeiconsIcon icon={Delete02Icon} size={16} />
			Delete
		</Button>
	</div>
</div>

{#if loading}
	<div class="grid gap-6 lg:grid-cols-2">
		{#each Array.from({ length: 4 }, (_, i) => i) as i (i)}
			<div class="bg-muted/40 h-64 animate-pulse rounded-xl border"></div>
		{/each}
	</div>
{:else if expo}
	<div class="grid items-start gap-6 lg:grid-cols-2">
		<!-- Left column: configuration plan + backend -->
		<div class="space-y-6">
			<!-- ─── Configuration Plan (operations + capabilities) ── -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-center gap-2">
					<HugeiconsIcon icon={Configuration01Icon} size={18} class="text-muted-foreground" />
					<Card.Title class="text-base">Configuration plan</Card.Title>
				</div>
				<Card.Description>
					{#if str(plan?.name)}
						<span class="text-foreground font-medium">{str(plan?.name)}</span>
						{#if str(plan?.description)}<span> — {str(plan?.description)}</span>{/if}
					{:else}
						Operations and capabilities exposed to the LLM.
					{/if}
				</Card.Description>
			</Card.Header>
			<Card.Content class="space-y-4 text-sm">
				<!-- Operations -->
				<PlanOperationsView {plan} />

				<!-- Capabilities -->
				<div class="border-t pt-4">
					<PlanCapabilitiesView {plan} {artifacts} />
				</div>

				{#if str(plan?.id)}
					<a href="/services/{serviceId}/plans/{str(plan?.id)}" class="text-primary inline-flex items-center gap-1 text-xs hover:underline">
						Open configuration plan <HugeiconsIcon icon={ArrowRight01Icon} size={14} />
					</a>
				{/if}
			</Card.Content>
		</Card.Root>

		<!-- ─── Backend ─────────────────────────────────────── -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-center gap-2">
					<HugeiconsIcon icon={CloudServerIcon} size={18} class="text-muted-foreground" />
					<Card.Title class="text-base">Backend</Card.Title>
				</div>
				<Card.Description>Where and how reShapr calls the underlying API.</Card.Description>
			</Card.Header>
			<Card.Content>
				<PlanBackendView {plan} {backendSecret} />
			</Card.Content>
		</Card.Root>
		</div>

		<!-- ─── Access (gateways, URLs, auth) ───────────────── -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-center gap-2">
					<HugeiconsIcon icon={McpServerIcon} size={18} class="text-muted-foreground" />
					<Card.Title class="text-base">Access</Card.Title>
				</div>
				<Card.Description>Where clients reach this MCP server, and how they authenticate.</Card.Description>
			</Card.Header>
			<Card.Content class="space-y-4 text-sm">
				<!-- Gateway group -->
				<div>
					<div class="text-muted-foreground mb-1 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={TagsIcon} size={13} /> Gateway group
					</div>
					{#if str(gg?.name) || str(gg?.id)}
						<div class="flex flex-wrap items-center gap-2">
							<Badge variant="secondary" class="gap-1 text-xs">
								<HugeiconsIcon icon={TagsIcon} size={12} />
								{str(gg?.name) ?? '—'}
							</Badge>
							{#if ggLabels.length > 0}
								{#each ggLabels as [k, v] (k)}
									<Badge
											class="border-transparent font-mono text-[10px] text-white"
											style="background-color: {avatarColor(k)};"
									>
										{k}={v}
									</Badge>
								{/each}
							{/if}
						</div>
					{:else}
						<span class="text-muted-foreground text-xs">—</span>
					{/if}
				</div>

				<!-- Gateways -->
				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={ApiGatewayIcon} size={13} /> Gateways
					</div>
					{#if !isActive}
						<p class="text-muted-foreground text-xs">
							Inactive — no gateway currently serves this MCP server.
						</p>
					{:else if gateways.length === 0}
						<p class="text-muted-foreground text-xs">No gateway attached.</p>
					{:else}
						<div class="flex flex-wrap gap-1.5">
							{#each gateways as g (g.id)}
								<Badge variant="outline" class="gap-1" title={g.fqdns.join(', ')}>
									<HugeiconsIcon icon={ApiGatewayIcon} size={12} />
									{g.name ?? g.id}
								</Badge>
							{/each}
						</div>
					{/if}
				</div>

				<!-- Endpoint URLs -->
				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={Link01Icon} size={13} /> Endpoint URLs
					</div>
					{#if endpointUrls.length === 0}
						<p class="text-muted-foreground text-xs">
							{isActive ? 'No endpoint URL available.' : 'Available once the server is active.'}
						</p>
					{:else}
						<div class="flex flex-col gap-1.5">
							{#each endpointUrls as url (url)}
								<div class="bg-muted/50 flex min-w-0 items-center gap-1 rounded-md px-2 py-1">
									<code class="min-w-0 flex-1 truncate font-mono text-xs" title={url}>{url}</code>
									<button
										type="button"
										class="text-muted-foreground hover:text-foreground shrink-0 rounded p-0.5 transition-colors"
										title="Copy URL"
										aria-label="Copy URL"
										onclick={() => void copyUrl(url)}
									>
										<HugeiconsIcon
											icon={copiedUrl === url ? Tick02Icon : Copy01Icon}
											size={14}
											class={copiedUrl === url ? 'text-primary' : ''}
										/>
									</button>
								</div>
							{/each}
						</div>
					{/if}
				</div>

				<!-- MCP authentication -->
				<div class="border-t pt-4">
					<PlanClientAuthView {plan} />
				</div>

				<!-- Audit -->
				<div class="border-t pt-4">
					<PlanAuditView {plan} />
				</div>
			</Card.Content>
		</Card.Root>
	</div>
{/if}

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete MCP server"
	description={`You are about to delete the MCP server "${heroTitle}". This action cannot be undone.`}
	confirmLabel="Delete"
	onConfirm={confirmDeleteExposition}
>
	<p class="text-muted-foreground text-sm">
		The MCP server endpoint will be taken down. Any client currently connected to this exposition
		will immediately lose access.
	</p>
</ConfirmDialog>

