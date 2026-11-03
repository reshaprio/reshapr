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
	import { tick } from 'svelte';
	import { apiClient, ApiError } from '#lib/api/client.js';
	import { quotaEntry } from '#lib/dashboardStatsCompute.js';
	import { avatarColor, avatarInitials } from '#lib/avatarColor.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import CreateExpositionDrawer from '#lib/components/exposition/CreateExpositionDrawer.svelte';
	import OrganizationBadge from '#lib/components/OrganizationBadge.svelte';
	import PageHeader from '#lib/components/PageHeader.svelte';
	import QuotaGauge, { type QuotaInfo } from '#lib/components/QuotaGauge.svelte';
	import { auth } from '#lib/stores/auth.svelte.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import * as Card from '#lib/components/ui/card/index.js';
	import { Switch } from '#lib/components/ui/switch/index.js';
	import { Label } from '#lib/components/ui/label/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { ApiIcon, CloudServerIcon, Configuration01Icon, Copy01Icon, Link01Icon, McpServerIcon, Tick02Icon, RefreshIcon } from '@hugeicons/core-free-icons';

	const QUOTA_METRIC = 'exposition.count';

	type ExpoRow = {
		id: string;
		name: string | null;
		serviceName: string;
		service: string;
		plan: string | null;
		backend: string;
		endpoints: string;
		endpointUrls: string[];
		organizationId: string | null;
	};

	let mode = $state<'active' | 'all'>('active');
	let rows = $state<ExpoRow[]>([]);
	let error = $state<string | null>(null);

	// ── Quota state ───────────────────────────────────────────
	let quota = $state<QuotaInfo>(null);
	// When no quota entry is returned, creation is not restricted.
	const canCreate = $derived(quota === null || quota.remaining > 0);

	function serviceName(service: unknown): string {
		if (!service || typeof service !== 'object') return '';
		const s = service as Record<string, unknown>;
		return typeof s.name === 'string' ? s.name : '';
	}

	function serviceLabel(service: unknown): string {
		if (!service || typeof service !== 'object') return '—';
		const s = service as Record<string, unknown>;
		const name = typeof s.name === 'string' ? s.name : '';
		const version = typeof s.version === 'string' ? s.version : '';
		if (name && version) return `${name}:${version}`;
		if (name) return name;
		return '—';
	}

	function backendUrl(configurationPlan: unknown): string {
		if (!configurationPlan || typeof configurationPlan !== 'object') return '—';
		const c = configurationPlan as Record<string, unknown>;
		return typeof c.backendEndpoint === 'string' ? c.backendEndpoint : '—';
	}

	function planLabel(configurationPlan: unknown): string | null {
		if (!configurationPlan || typeof configurationPlan !== 'object') return null;
		const c = configurationPlan as Record<string, unknown>;
		const name = typeof c.name === 'string' ? c.name.trim() : '';
		return name || null;
	}

	function endpointsLabel(raw: Record<string, unknown>): string {
		const cp = raw.configurationPlan;
		if (cp && typeof cp === 'object') {
			const c = cp as Record<string, unknown>;
			const inc = c.includedOperations;
			if (Array.isArray(inc)) return String(inc.length);
		}
		const gws = raw.gateways;
		if (Array.isArray(gws)) {
			let n = 0;
			for (const g of gws) {
				if (g && typeof g === 'object') {
					const fq = (g as Record<string, unknown>).fqdns;
					if (Array.isArray(fq)) n += fq.length;
				}
			}
			if (n > 0) return String(n);
		}
		return '—';
	}

	/** Collect the unique FQDNs declared by all gateways of an exposition. */
	function gatewayFqdns(raw: Record<string, unknown>): string[] {
		const gws = raw.gateways;
		const all: string[] = [];
		if (Array.isArray(gws)) {
			for (const g of gws) {
				if (g && typeof g === 'object') {
					const fq = (g as Record<string, unknown>).fqdns;
					if (Array.isArray(fq)) {
						for (const f of fq) {
							if (typeof f === 'string' && f && !all.includes(f)) all.push(f);
						}
					}
				}
			}
		}
		return all;
	}

	/**
	 * Compute the public base URL (scheme + host) for a gateway FQDN.
	 * localhost hosts use http://, everything else uses https://.
	 */
	function endpointBase(fqdn: string): string {
		if (/^https?:\/\//i.test(fqdn)) return fqdn.replace(/\/+$/, '');
		const host = fqdn.split(/[:/]/, 1)[0].toLowerCase();
		const scheme = host === 'localhost' || host === '127.0.0.1' ? 'http' : 'https';
		return `${scheme}://${fqdn}`;
	}

	const encSeg = (s: string) => s.replace(/\s/g, '+');

	/**
	 * Build the deterministic MCP endpoint URLs for a gateway FQDN:
	 *  - always the per-id endpoint /mcp/{expositionId}
	 *  - the per-name endpoint /mcp/{org}/{expositionName} only when the exposition is named
	 */
	function buildEndpointUrls(o: Record<string, unknown>): string[] {
		const id = typeof o.id === 'string' ? o.id : '';
		const org = typeof o.organizationId === 'string' ? o.organizationId : '';
		const name = typeof o.name === 'string' && o.name.trim() ? o.name.trim() : '';
		if (!id) return [];
		const urls: string[] = [];
		for (const fqdn of gatewayFqdns(o)) {
			const base = endpointBase(fqdn);
			if (name && org) urls.push(`${base}/mcp/${org}/${encSeg(name)}`);
			urls.push(`${base}/mcp/${encSeg(id)}`);
		}
		return urls;
	}

	function toExpoRow(raw: unknown): ExpoRow | null {
		if (!raw || typeof raw !== 'object') return null;
		const o = raw as Record<string, unknown>;
		if (typeof o.id !== 'string') return null;
		return {
			id: o.id,
			name: typeof o.name === 'string' && o.name.trim() ? o.name.trim() : null,
			serviceName: serviceName(o.service),
			service: serviceLabel(o.service),
			plan: planLabel(o.configurationPlan),
			backend: backendUrl(o.configurationPlan),
			endpoints: endpointsLabel(o),
			endpointUrls: buildEndpointUrls(o),
			organizationId: typeof o.organizationId === 'string' ? o.organizationId : null
		};
	}

	async function refreshQuota() {
		try {
			quota = quotaEntry(await apiClient().getQuotas(), QUOTA_METRIC);
		} catch {
			// Non-critical: leave quota as-is so the UI stays usable.
		}
	}

	async function load() {
		error = null;
		try {
			const [data] = await Promise.all([
				mode === 'active'
					? (apiClient().listExpositionsActive() as Promise<unknown[]>)
					: (apiClient().listExpositionsAll() as Promise<unknown[]>),
				refreshQuota()
			]);
			const list = Array.isArray(data) ? data : [];
			rows = list.map(toExpoRow).filter((r): r is ExpoRow => r != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	$effect(() => {
		void mode;
		void load();
	});

	// Copy an endpoint URL to the clipboard, tracking the last-copied value so
	// the button can briefly show a confirmation icon.
	let copiedUrl = $state<string | null>(null);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;

	async function copyUrl(url: string) {
		try {
			await navigator.clipboard.writeText(url);
			copiedUrl = url;
			clearTimeout(copiedTimer);
			copiedTimer = setTimeout(() => (copiedUrl = null), 1500);
		} catch {
			// Clipboard may be unavailable (e.g. insecure context); ignore.
		}
	}

	// ── Create drawer state ───────────────────────────────────
	// Actual creation flow lives in the shared CreateExpositionDrawer component.
	let drawerOpen = $state(false);

	// Force a clean open transition so the drawer reliably reopens even if a
	// previous user-initiated close left `drawerOpen` out of sync.
	async function openCreate() {
		if (!canCreate) return;
		drawerOpen = false;
		await tick();
		drawerOpen = true;
	}
</script>

<PageHeader title="MCP Servers" subtitle="Expositions of your services as MCP servers.">
	{#snippet actions()}
		<div class="flex flex-wrap items-center gap-4">
			<div class="flex items-center gap-2">
				<Label for="expo-mode-switch" class="text-muted-foreground text-sm">
					{mode === 'active' ? 'Active' : 'All'}
				</Label>
				<Switch
					id="expo-mode-switch"
					checked={mode === 'all'}
					onCheckedChange={(v) => (mode = v ? 'all' : 'active')}
					aria-label="Show all MCP servers"
				/>
			</div>
			<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" onclick={() => void load()}>
				<HugeiconsIcon icon={RefreshIcon} size={16} />
			</Button>
			<Button onclick={() => void openCreate()} disabled={!canCreate} title={canCreate ? undefined : 'Quota reached'}>
				New MCP Server
			</Button>
		</div>
	{/snippet}
</PageHeader>

{#if quota}
	<div class="mb-4">
		<QuotaGauge
			{quota}
			label="MCP server quota"
			fullMessage="Quota reached. Delete an existing MCP server to create a new one."
		/>
	</div>
{/if}


{#if error}
	<ApiErrorAlert message={error} />
{/if}

{#if rows.length === 0 && !error}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No MCP servers yet.</p>
		<Button class="mt-3" size="sm" onclick={() => void openCreate()} disabled={!canCreate}>
			Create your first MCP server
		</Button>
	</div>
{:else}
	<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
		{#each rows as x (x.id)}
			<a href="/expositions/{x.id}" class="block">
				<Card.Root
					class="flex h-full flex-col transition-colors hover:border-primary/50 hover:bg-accent/50"
				>
					<Card.Header class="flex-1">
						<div class="flex min-w-0 items-start gap-3">
							<!-- Service initials badge, colored from the reShapr-derived palette -->
							<span
								class="flex size-10 shrink-0 items-center justify-center rounded-lg text-sm font-semibold text-white"
								style="background-color: {avatarColor(x.serviceName || x.service)};"
								aria-hidden="true"
							>
								{avatarInitials(x.serviceName || x.service)}
							</span>
							<div class="min-w-0 flex-1">
								{#if x.name}
									<Card.Title class="text-base leading-snug break-all">
										{x.name}
									</Card.Title>
								{:else}
									<Card.Title class="text-base leading-snug break-all">
										{x.service}
									</Card.Title>
								{/if}
								<Card.Description class="mt-1 truncate">
									<code class="bg-muted rounded px-1 py-0.5 font-mono text-xs">{x.id}</code>
								</Card.Description>
							</div>
							<span
								class="text-muted-foreground/70 shrink-0"
								aria-hidden="true"
								title="MCP Server"
							>
								<HugeiconsIcon icon={McpServerIcon} size={20} />
							</span>
						</div>
					</Card.Header>
					<Card.Content class="space-y-3 pt-0 text-xs">
						<!-- Service reference -->
						<div class="flex items-start gap-2">
							<HugeiconsIcon
									icon={ApiIcon}
									size={16}
									class="text-muted-foreground mt-0.5 shrink-0"
							/>
							<p
									class="min-w-0 flex-1 truncate rounded px-1.5 py-0.5"
									title={x.service}
							>
								{x.service}
							</p>
						</div>

						<!-- Configuration plan -->
						{#if x.plan}
							<div class="flex items-start gap-2">
								<HugeiconsIcon
									icon={Configuration01Icon}
									size={16}
									class="text-muted-foreground mt-0.5 shrink-0"
								/>
								<p
									class="min-w-0 flex-1 truncate rounded px-1.5 py-0.5"
									title={x.plan}
								>
									{x.plan}
								</p>
							</div>
						{/if}

						<!-- Backend endpoint -->
						<div class="flex items-start gap-2">
							<HugeiconsIcon
								icon={CloudServerIcon}
								size={16}
								class="text-muted-foreground mt-0.5 shrink-0"
							/>
							<code
								class="min-w-0 flex-1 truncate rounded px-1.5 py-0.5 font-mono"
								title={x.backend}
							>
								{x.backend}
							</code>
						</div>

						<!-- MCP endpoint URLs -->
						<div class="flex items-start gap-2">
							<HugeiconsIcon
								icon={Link01Icon}
								size={16}
								class="text-muted-foreground mt-0.5 shrink-0"
							/>
							{#if x.endpointUrls.length === 0}
								<span class="text-muted-foreground">No active endpoint</span>
							{:else}
								<div class="flex min-w-0 flex-1 flex-col gap-1">
									{#each x.endpointUrls as url (url)}
										<div class="flex min-w-0 items-center gap-1">
											<code class="min-w-0 flex-1 truncate font-mono" title={url}>
												{url}
											</code>
											<button
												type="button"
												class="text-muted-foreground hover:text-foreground shrink-0 rounded p-0.5 transition-colors"
												title="Copy URL"
												aria-label="Copy URL"
												onclick={(e) => {
													e.preventDefault();
													e.stopPropagation();
													void copyUrl(url);
												}}
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

						{#if auth.isAdmin && x.organizationId}
							<div class="flex justify-end">
								<OrganizationBadge organizationName={x.organizationId} />
							</div>
						{/if}
					</Card.Content>
				</Card.Root>
			</a>
		{/each}
	</div>
{/if}

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Create MCP Server Drawer (shared guided wizard)             -->
<!-- ═══════════════════════════════════════════════════════════ -->
<CreateExpositionDrawer bind:open={drawerOpen} onCreated={() => void load()} />

