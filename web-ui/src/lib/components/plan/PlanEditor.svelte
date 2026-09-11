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
	import { Checkbox } from '$lib/components/ui/checkbox/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import * as Select from '$lib/components/ui/select/index.js';
	import { parseArtifactRefList, type ArtifactRef, type ArtifactType } from '$lib/artifacts/index.js';
	import { parseServiceRecord } from '$lib/serviceHub.js';
	import { parseOperationsList, formatOperationsList } from '$lib/operationsList.js';
	import { cn } from '$lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		Search01Icon,
		Wrench01Icon,
		BubbleChatIcon,
		File01Icon,
		FilterIcon,
		ArrowRight01Icon,
		Copy01Icon,
		Tick02Icon,
		Cancel01Icon,
		Delete02Icon
	} from '@hugeicons/core-free-icons';

	// ── Props ─────────────────────────────────────────────────────────────────
	let { serviceId, planId = null }: { serviceId: string; planId?: string | null } = $props();

	const isEdit = $derived(planId != null);

	// ── Types ─────────────────────────────────────────────────────────────────
	type ServiceOperation = {
		name: string;
		method: string | null;
		action: string | null;
	};

	type SecretOption = { id: string; name: string; type: string };

	function str(v: unknown): string | null {
		return typeof v === 'string' && v.trim() !== '' ? v : null;
	}

	// ── Loaded reference data ───────────────────────────────────────────────────
	let operations = $state<ServiceOperation[]>([]);
	let artifacts = $state<ArtifactRef[]>([]);
	let secrets = $state<SecretOption[]>([]);
	let serviceName = $state('');
	let serviceVersion = $state('');
	/** The full plan document loaded in edit mode; preserved on save to keep unknown fields. */
	let basePlan = $state<Record<string, unknown>>({});

	// ── Template support (create mode only) ────────────────────────────────────
	type TemplateOption = { id: string; name: string; oauth2Configuration?: any };
	let templates = $state<TemplateOption[]>([]);
	let selectedTemplateId = $state<string | undefined>(undefined);

	// ── Form state ──────────────────────────────────────────────────────────────
	let name = $state('');
	let description = $state('');
	let backendEndpoint = $state('');
	let backendTimeout = $state('');
	let audit = $state(false);
	let backendSecretId = $state('');

	// ── Caching configuration (MCP >= 2026-07-28) ───────────────────────────────
	let cachingTtlMs = $state('');
	let cachingScope = $state<'public' | 'private' | ''>('');

	/** 'include' exposes only the selected operations, 'exclude' hides the selected ones. */
	let opsMode = $state<'include' | 'exclude'>('include');
	let selectedOps = $state<string[]>([]);
	let opsQuery = $state('');

	/** Selected attached artifact names. Empty means "include all attached artifacts". */
	let includedArtifacts = $state<string[]>([]);

	// ── MCP endpoint authentication ───────────────────────────────────────────────
	/** How clients authenticate against the exposed MCP endpoint. */
	let mcpAuthMode = $state<'none' | 'apikey' | 'oauth'>('none');
	let oauthAuthServersText = $state('');
	let oauthJwksUri = $state('');
	let oauthScopesText = $state('');

	// ── UI state ────────────────────────────────────────────────────────────────
	let loading = $state(true);
	let saving = $state(false);
	let error = $state<string | null>(null);

	// API key banner (shown once after creation or renewal), mirroring the API tokens UX.
	let createdKey = $state<string | null>(null);
	let copied = $state(false);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;

	// Persist the freshly created key across the redirect to the plan's edit page.
	const CREATED_KEY_STORAGE = 'reshapr:plan-created-apikey';

	async function copyCreatedKey() {
		if (!createdKey) return;
		try {
			await navigator.clipboard.writeText(createdKey);
			copied = true;
			clearTimeout(copiedTimer);
			copiedTimer = setTimeout(() => (copied = false), 1500);
		} catch {
			// Clipboard may be unavailable (e.g. insecure context); ignore.
		}
	}

	// Optional sections (2–5) are collapsed by default; a short summary is shown when collapsed.
	let openOps = $state(false);
	let openArtifacts = $state(false);
	let openMcp = $state(false);
	let openBackend = $state(false);

	const NONE_SECRET = '__none__';

	// ── Derived helpers ─────────────────────────────────────────────────────────
	const filteredOps = $derived.by(() => {
		const q = opsQuery.trim().toLowerCase();
		if (!q) return operations;
		return operations.filter(
			(o) =>
				o.name.toLowerCase().includes(q) ||
				(o.method?.toLowerCase().includes(q) ?? false) ||
				(o.action?.toLowerCase().includes(q) ?? false)
		);
	});

	const selectedSecretLabel = $derived.by(() => {
		if (!backendSecretId) return 'None (public backend)';
		const found = secrets.find((s) => s.id === backendSecretId);
		return found ? found.name : backendSecretId;
	});

	// Custom artifacts (non-main) grouped by type for the capability selector.
	type ArtifactGroup = { type: ArtifactType; label: string; items: ArtifactRef[] };

	const CUSTOM_TYPES: { type: ArtifactType; label: string }[] = [
		{ type: 'RESHAPR_CUSTOM_TOOLS', label: 'Custom tools' },
		{ type: 'RESHAPR_PROMPTS', label: 'Prompts' },
		{ type: 'RESHAPR_RESOURCES', label: 'Resources' },
		{ type: 'RESHAPR_TOOLS_OUTPUT_FILTERS', label: 'Output filters' }
	];

	const CAPABILITY_ICONS: Record<string, typeof Wrench01Icon> = {
		RESHAPR_CUSTOM_TOOLS: Wrench01Icon,
		RESHAPR_PROMPTS: BubbleChatIcon,
		RESHAPR_RESOURCES: File01Icon,
		RESHAPR_TOOLS_OUTPUT_FILTERS: FilterIcon
	};

	const CAPABILITY_STYLES: Record<string, string> = {
		RESHAPR_CUSTOM_TOOLS: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
		RESHAPR_PROMPTS: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
		RESHAPR_RESOURCES:
			'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
		RESHAPR_TOOLS_OUTPUT_FILTERS:
			'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
	};

	const artifactGroups = $derived.by<ArtifactGroup[]>(() =>
		CUSTOM_TYPES.map(({ type, label }) => ({
			type,
			label,
			items: artifacts.filter((a) => a.type === type && !a.mainArtifact)
		})).filter((g) => g.items.length > 0)
	);

	const totalArtifacts = $derived(artifactGroups.reduce((n, g) => n + g.items.length, 0));

	// Artifacts section is hidden once loaded when the service has no custom artifacts attached.
	const showArtifacts = $derived(loading || totalArtifacts > 0);
	// Backend & MCP authentication section numbers shift depending on the Artifacts section visibility.
	const mcpSectionNo = $derived(showArtifacts ? 4 : 3);
	const backendSectionNo = $derived(showArtifacts ? 5 : 4);

	// Color-coded pill per HTTP verb.
	const METHOD_STYLES: Record<string, string> = {
		GET: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
		POST: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
		PUT: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400',
		PATCH: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
		DELETE: 'bg-rose-500/10 text-rose-600 ring-rose-500/20 dark:text-rose-400'
	};

	function methodStyle(label: string | null): string {
		const key = (label ?? '').toUpperCase();
		return METHOD_STYLES[key] ?? 'bg-muted text-muted-foreground ring-border';
	}

	// ── Collapsed-section summaries ──────────────────────────────────────────────
	const opsSummary = $derived(
		selectedOps.length === 0
			? 'All operations'
			: `${opsMode === 'include' ? 'Including' : 'Excluding'} ${selectedOps.length}`
	);
	const artifactsSummary = $derived(
		includedArtifacts.length === 0 ? 'All artifacts' : `${includedArtifacts.length} selected`
	);
	const MCP_AUTH_LABELS: Record<'none' | 'apikey' | 'oauth', string> = {
		none: 'None',
		apikey: 'API Key',
		oauth: 'OAuth'
	};
	const mcpSummary = $derived(MCP_AUTH_LABELS[mcpAuthMode]);
	const backendSummary = $derived(selectedSecretLabel);

	// ── Load ────────────────────────────────────────────────────────────────────
	function parseOperations(raw: unknown): ServiceOperation[] {
		const o = raw as Record<string, unknown> | null;
		const ops = o && Array.isArray(o.operations) ? o.operations : [];
		return ops
			.map((it): ServiceOperation | null => {
				if (!it || typeof it !== 'object') return null;
				const r = it as Record<string, unknown>;
				if (typeof r.name !== 'string') return null;
				return { name: r.name, method: str(r.method), action: str(r.action) };
			})
			.filter((it): it is ServiceOperation => it != null);
	}

	async function load() {
		if (!serviceId) return;
		loading = true;
		error = null;
		try {
			const [service, refs, secretRefs] = await Promise.all([
				apiClient().getService(serviceId),
				apiClient()
					.listArtifactRefsByService(serviceId)
					.catch(() => [] as unknown[]),
				apiClient()
					.listSecretRefs()
					.catch(() => [] as unknown[])
			]);

			operations = parseOperations(service);
			const record = parseServiceRecord(service);
			serviceName = record?.name && record.name !== '—' ? record.name : '';
			serviceVersion = record?.version && record.version !== '—' ? record.version : '';
			artifacts = parseArtifactRefList(refs);
			secrets = (Array.isArray(secretRefs) ? secretRefs : [])
				.map((s): SecretOption | null => {
					if (!s || typeof s !== 'object') return null;
					const o = s as Record<string, unknown>;
					if (typeof o.id !== 'string' || typeof o.name !== 'string') return null;
					return { id: o.id, name: o.name, type: typeof o.type === 'string' ? o.type : '' };
				})
				.filter((s): s is SecretOption => s != null)
				// Backend/endpoint secrets are the ones usable to authenticate the proxied backend.
				.filter((s) => s.type === 'ENDPOINT' || s.type === '');

			if (isEdit && planId) {
				const plan = (await apiClient().getConfigurationPlan(planId)) as Record<string, unknown>;
				applyPlan(plan);
				consumeCreatedKey(planId);
			} else if (!name.trim()) {
				// Suggest a simple default name; the operator can rename it freely.
				name = 'my-plan';
			}
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	function applyPlan(plan: Record<string, unknown>) {
		basePlan = plan;
		name = typeof plan.name === 'string' ? plan.name : '';
		description = typeof plan.description === 'string' ? plan.description : '';
		backendEndpoint = typeof plan.backendEndpoint === 'string' ? plan.backendEndpoint : '';
		backendTimeout =
			plan.backendTimeout != null && plan.backendTimeout !== '' ? String(plan.backendTimeout) : '';
		audit = plan.audit === true;
		backendSecretId = typeof plan.backendSecretId === 'string' ? plan.backendSecretId : '';

		// Caching configuration
		const cc = plan.cachePolicy as Record<string, unknown> | null | undefined;
		cachingTtlMs = cc?.ttlMs != null ? String(cc.ttlMs) : '';
		cachingScope = cc?.cacheScope === 'private' ? 'private' : cc?.cacheScope === 'public' ? 'public' : '';

		const included = Array.isArray(plan.includedOperations)
			? plan.includedOperations.map(String).filter(Boolean)
			: [];
		const excluded = Array.isArray(plan.excludedOperations)
			? plan.excludedOperations.map(String).filter(Boolean)
			: [];
		if (excluded.length && !included.length) {
			opsMode = 'exclude';
			selectedOps = excluded;
		} else {
			opsMode = 'include';
			selectedOps = included;
		}

		includedArtifacts = Array.isArray(plan.includedArtifacts)
			? plan.includedArtifacts.map(String).filter(Boolean)
			: [];

		// MCP endpoint authentication: OAuth2 takes precedence, then an existing API key, else none.
		const oauth =
			plan.oauth2Configuration && typeof plan.oauth2Configuration === 'object'
				? (plan.oauth2Configuration as Record<string, unknown>)
				: null;
		if (oauth) {
			mcpAuthMode = 'oauth';
			oauthAuthServersText = formatOperationsList(oauth.authorizationServers);
			oauthJwksUri = typeof oauth.jwksUri === 'string' ? oauth.jwksUri : '';
			oauthScopesText = formatOperationsList(oauth.scopes);
		} else if (typeof plan.apiKey === 'string' && plan.apiKey.trim() !== '') {
			mcpAuthMode = 'apikey';
		} else {
			mcpAuthMode = 'none';
		}
	}

	$effect(() => {
		// Re-run whenever the target changes.
		void serviceId;
		void planId;
		void load();
	});

	/** Show (once) the API key stashed just before the post-create redirect. */
	function consumeCreatedKey(id: string) {
		try {
			const raw = sessionStorage.getItem(CREATED_KEY_STORAGE);
			if (!raw) return;
			sessionStorage.removeItem(CREATED_KEY_STORAGE);
			const parsed = JSON.parse(raw) as { id?: string; key?: string };
			if (parsed.id === id && typeof parsed.key === 'string') {
				createdKey = parsed.key;
			}
		} catch {
			// Ignore malformed/unavailable storage.
		}
	}

	// ── Selection helpers ────────────────────────────────────────────────────────
	function toggleOp(nameValue: string, checked: boolean) {
		if (checked) {
			if (!selectedOps.includes(nameValue)) selectedOps = [...selectedOps, nameValue];
		} else {
			selectedOps = selectedOps.filter((n) => n !== nameValue);
		}
	}

	function selectAllOps() {
		selectedOps = operations.map((o) => o.name);
	}
	function clearOps() {
		selectedOps = [];
	}

	function toggleArtifact(nameValue: string, checked: boolean) {
		if (checked) {
			if (!includedArtifacts.includes(nameValue))
				includedArtifacts = [...includedArtifacts, nameValue];
		} else {
			includedArtifacts = includedArtifacts.filter((n) => n !== nameValue);
		}
	}

	function onSecretChange(v: string) {
		backendSecretId = v === NONE_SECRET ? '' : v;
	}

	// ── Save ──────────────────────────────────────────────────────────────────────
	async function onSubmit(ev: SubmitEvent) {
		ev.preventDefault();
		error = null;
		createdKey = null;

		if (!name.trim() || !backendEndpoint.trim()) {
			error = 'Name and backend endpoint are required.';
			return;
		}

		const body: Record<string, unknown> = { ...basePlan };
		body.name = name.trim();
		body.serviceId = serviceId;
		body.backendEndpoint = backendEndpoint.trim();

		if (description.trim()) body.description = description.trim();
		else delete body.description;

		if (backendSecretId) body.backendSecretId = backendSecretId;
		else delete body.backendSecretId;

		const timeout = backendTimeout.trim();
		if (timeout && !Number.isNaN(Number(timeout))) body.backendTimeout = Number(timeout);
		else delete body.backendTimeout;

		body.audit = audit;

		// Caching configuration — send only when at least one field is set; null to clear.
		const ttlNum = cachingTtlMs.trim();
		if (ttlNum || cachingScope) {
			body.cachePolicy = {
				...(ttlNum && !Number.isNaN(Number(ttlNum)) ? { ttlMs: Number(ttlNum) } : {}),
				...(cachingScope ? { cacheScope: cachingScope } : {})
			};
		} else {
			body.cachePolicy = null;
		}

		delete body.includedOperations;
		delete body.excludedOperations;
		if (selectedOps.length) {
			if (opsMode === 'include') body.includedOperations = selectedOps;
			else body.excludedOperations = selectedOps;
		}

		if (includedArtifacts.length) body.includedArtifacts = includedArtifacts;
		else delete body.includedArtifacts;

		// MCP endpoint authentication.
		if (mcpAuthMode === 'oauth') {
			body.oauth2Configuration = {
				authorizationServers: parseOperationsList(oauthAuthServersText),
				jwksUri: oauthJwksUri.trim() || null,
				scopes: parseOperationsList(oauthScopesText)
			};
			delete body.apiKey;
		} else {
			delete body.oauth2Configuration;
			if (mcpAuthMode === 'apikey') {
				// On create, ask the backend to generate a key; on edit, keep the existing one.
				if (!isEdit) body.apiKey = 'generate-me';
			} else {
				delete body.apiKey;
			}
		}

		saving = true;
		try {
			if (isEdit && planId) {
				await apiClient().updateConfigurationPlan(planId, body);
				await load();
			} else {
				const out = (await apiClient().createConfigurationPlan(body)) as {
					id: string;
					apiKey?: string;
				};
				// Stash the freshly generated key so the edit page can reveal it once.
				if (out.apiKey) {
					try {
						sessionStorage.setItem(
							CREATED_KEY_STORAGE,
							JSON.stringify({ id: out.id, key: out.apiKey })
						);
					} catch {
						// Ignore unavailable storage; the key can still be renewed later.
					}
				}
				await goto(`/services/${serviceId}/plans/${out.id}`);
			}
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			saving = false;
		}
	}

	async function onRenew() {
		if (!planId) return;
		error = null;
		try {
			const out = (await apiClient().renewApiKey(planId)) as { apiKey?: string };
			createdKey = out.apiKey ?? '(see server response)';
			copied = false;
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	let deleteOpen = $state(false);

	function onDelete() {
		if (!planId) return;
		deleteOpen = true;
	}

	async function confirmDeletePlan() {
		if (!planId) return;
		await apiClient().deleteConfigurationPlan(planId);
		await goto(`/services/${serviceId}/plans`);
	}

	// ── Load templates (create mode only) ───────────────────────────────────────
	async function loadTemplates() {
		try {
			const raw = await apiClient().listConfigurationTemplates();
			templates = (raw as any[]).map((t) => ({
				id: String(t.id),
				name: String(t.name),
				oauth2Configuration: t.oauth2Configuration ?? null
			}));
		} catch {
			// Non-critical: if templates cannot be loaded, the selector is simply empty.
		}
	}

	/**
	 * Applies the OAuth2 configuration from the selected template to the current form fields.
	 * Only pre-fills — the user can freely edit the values afterwards.
	 */
	function applyTemplate(templateId: string) {
		const tpl = templates.find((t) => t.id === templateId);
		if (!tpl) return;
		if (tpl.oauth2Configuration) {
			mcpAuthMode = 'oauth';
			oauthAuthServersText = formatOperationsList(
				tpl.oauth2Configuration.authorizationServers ?? []
			);
			oauthJwksUri = tpl.oauth2Configuration.jwksUri ?? '';
			oauthScopesText = formatOperationsList(tpl.oauth2Configuration.scopes ?? []);
			// Open the MCP section so the user can see the pre-filled values.
			openMcp = true;
		}
	}

	$effect(() => {
		if (!isEdit) void loadTemplates();
	});
</script>

<form class="space-y-6" onsubmit={onSubmit}>
	{#snippet sectionHead(
		title: string,
		description: string,
		open: boolean,
		toggle: () => void,
		summary: string
	)}
		<button
			type="button"
			onclick={toggle}
			class="flex w-full items-center gap-2 py-6 pr-6 pl-3 text-left"
			aria-expanded={open}
		>
			<span
				class="text-muted-foreground inline-flex shrink-0 transition-transform duration-200 {open
					? 'rotate-90'
					: ''}"
			>
				<HugeiconsIcon icon={ArrowRight01Icon} size={16} />
			</span>
			<div class="min-w-0 flex-1 space-y-1">
				<h3 class="text-base leading-none font-semibold">{title}</h3>
				{#if description}
					<p class="text-muted-foreground text-sm">{description}</p>
				{/if}
			</div>
			{#if !open && summary}
				<span class="text-muted-foreground max-w-[45%] shrink-0 truncate text-xs" title={summary}>
					{summary}
				</span>
			{/if}
		</button>
	{/snippet}

	<div class="flex flex-wrap items-center justify-between gap-3">
		<h2 class="text-lg font-semibold">
			{isEdit ? 'Edit configuration plan' : 'New configuration plan'}
		</h2>
		<div class="flex flex-wrap gap-2">
			{#if !isEdit && templates.length > 0}
				<Select.Root
					type="single"
					value={selectedTemplateId}
					onValueChange={(v) => {
						if (!v) return;
						selectedTemplateId = v;
						applyTemplate(v);
					}}
				>
					<Select.Trigger class="w-52" aria-label="Start from template">
						{templates.find((t) => t.id === selectedTemplateId)?.name || 'Start from template…'}
					</Select.Trigger>
					<Select.Content>
						{#each templates as tpl (tpl.id)}
							<Select.Item value={tpl.id}>{tpl.name}</Select.Item>
						{/each}
					</Select.Content>
				</Select.Root>
			{/if}
			{#if isEdit}
				{#if mcpAuthMode === 'apikey'}
					<Button type="button" variant="outline" disabled={loading} onclick={() => void onRenew()}>
						Renew API key
					</Button>
				{/if}
				<Button type="button" variant="destructive" disabled={loading} onclick={() => void onDelete()}>
					<HugeiconsIcon icon={Delete02Icon} size={16} />
					Delete
				</Button>
			{/if}
			<Button type="submit" disabled={loading || saving}>
				{saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create plan'}
			</Button>
		</div>
	</div>

	{#if createdKey}
		<div class="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4">
			<div class="flex items-start justify-between gap-3">
				<div class="min-w-0">
					<p class="text-sm font-semibold text-amber-700 dark:text-amber-400">
						Copy your API key now
					</p>
					<p class="text-muted-foreground mt-0.5 text-xs">
						This is the only time the API key value will be shown.
					</p>
					<code class="mt-2 block font-mono text-xs break-all">{createdKey}</code>
				</div>
				<div class="flex shrink-0 items-center gap-1">
					<Button variant="outline" size="sm" onclick={() => void copyCreatedKey()}>
						{#if copied}
							<HugeiconsIcon icon={Tick02Icon} size={16} />
							Copied
						{:else}
							<HugeiconsIcon icon={Copy01Icon} size={16} />
							Copy
						{/if}
					</Button>
					<Button
						variant="ghost"
						size="icon"
						onclick={() => (createdKey = null)}
						aria-label="Dismiss"
					>
						<HugeiconsIcon icon={Cancel01Icon} size={16} />
					</Button>
				</div>
			</div>
		</div>
	{/if}

	{#if error}
		<ApiErrorAlert message={error} />
	{/if}

	<!-- ── Section 1: General ──────────────────────────────────────────────── -->
	<Card.Root>
		<Card.Header>
			<Card.Title class="text-base">1. General</Card.Title>
			<Card.Description>Identify the plan and the backend it proxies.</Card.Description>
		</Card.Header>
		<Card.Content class="space-y-4">
			<div class="grid gap-4 sm:grid-cols-2">
				<div class="space-y-2">
					<Label for="name">Name <span class="text-destructive">*</span></Label>
					<Input id="name" bind:value={name} disabled={loading} required />
				</div>
				<div class="space-y-2">
					<Label for="description">Description</Label>
					<Input id="description" bind:value={description} disabled={loading} />
				</div>
			</div>
			<div class="grid gap-4 sm:grid-cols-2">
				<div class="space-y-2">
					<Label for="backendEndpoint"
						>Backend endpoint URL <span class="text-destructive">*</span></Label
					>
					<Input
						id="backendEndpoint"
						bind:value={backendEndpoint}
						class="w-full"
						placeholder="https://api.backend.acme.com"
						disabled={loading}
						required
					/>
				</div>
				<div class="space-y-2">
					<Label for="backendTimeout">Backend timeout (ms)</Label>
					<Input
						id="backendTimeout"
						bind:value={backendTimeout}
						inputmode="numeric"
						placeholder="Default"
						disabled={loading}
					/>
				</div>
			</div>
			<div class="flex flex-wrap items-center gap-6">
				<label class="flex items-center gap-2 text-sm">
					<Checkbox checked={audit} disabled={loading} onCheckedChange={(v) => (audit = v === true)} />
					<span>Enable audit log</span>
				</label>
			</div>

			<!-- ── Caching configuration ─────────────────────────────────────── -->
			<div class="border-t pt-4">
				<p class="mb-3 text-sm font-medium">Caching configuration</p>
				<p class="mb-4 text-xs text-muted-foreground">
					Client-side cache hints sent on MCP list/read responses (protocol ≥ 2026-07-28).
					Leave blank to use the defaults (30 000 ms, public).
				</p>
				<div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
					<div class="space-y-2">
						<Label for="cachingTtlMs">Cache TTL (ms)</Label>
						<Input
							id="cachingTtlMs"
							bind:value={cachingTtlMs}
							inputmode="numeric"
							placeholder="Default: 30000"
							disabled={loading}
						/>
					</div>
					<div class="space-y-2">
						<Label for="cachingScope">Cache scope</Label>
						<select
							id="cachingScope"
							bind:value={cachingScope}
							disabled={loading}
							class="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
						>
							<option value="">Default (public)</option>
							<option value="public">public</option>
							<option value="private">private</option>
						</select>
					</div>
				</div>
			</div>
		</Card.Content>
	</Card.Root>

	<!-- ── Section 2: Operations ───────────────────────────────────────────── -->
	<Card.Root>
		{@render sectionHead(
			'2. Operations',
			'Choose which service operations this plan exposes to the LLM.',
			openOps,
			() => (openOps = !openOps),
			opsSummary
		)}
		{#if openOps}
			<Card.Content class="space-y-4">
			<div class="flex flex-wrap items-center gap-2">
				<Select.Root type="single" value={opsMode} onValueChange={(v) => (opsMode = v as 'include' | 'exclude')}>
					<Select.Trigger class="w-56" disabled={loading}>
						{opsMode === 'include' ? 'Include selected only' : 'Exclude selected'}
					</Select.Trigger>
					<Select.Content>
						<Select.Item value="include">Include selected only</Select.Item>
						<Select.Item value="exclude">Exclude selected</Select.Item>
					</Select.Content>
				</Select.Root>
				<span class="text-muted-foreground text-xs">
					{selectedOps.length} / {operations.length} selected
				</span>
				<div class="ms-auto flex gap-2">
					<Button type="button" variant="outline" size="sm" disabled={loading} onclick={selectAllOps}>
						Select all
					</Button>
					<Button type="button" variant="outline" size="sm" disabled={loading} onclick={clearOps}>
						Clear
					</Button>
				</div>
			</div>

			<div class="relative">
				<HugeiconsIcon
					icon={Search01Icon}
					size={16}
					class="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2"
				/>
				<Input bind:value={opsQuery} placeholder="Filter operations…" class="pl-8" disabled={loading} />
			</div>

			{#if loading}
				<p class="text-muted-foreground text-sm">Loading operations…</p>
			{:else if operations.length === 0}
				<p class="text-muted-foreground text-sm">No operations registered on this service.</p>
			{:else if filteredOps.length === 0}
				<p class="text-muted-foreground text-sm">No operation matches “{opsQuery}”.</p>
			{:else}
				<div class="max-h-96 space-y-1 overflow-y-auto rounded-lg border p-2">
					{#each filteredOps as op (op.name)}
						{@const pill = op.method ?? op.action}
						<label
							class="hover:bg-muted/60 flex items-center gap-3 rounded-md px-2 py-1.5 text-sm"
						>
							<Checkbox
								checked={selectedOps.includes(op.name)}
								disabled={loading}
								onCheckedChange={(v) => toggleOp(op.name, v === true)}
							/>
							{#if pill}
								<span
									class={cn(
										'inline-flex w-16 shrink-0 justify-center rounded-md px-2 py-0.5 font-mono text-[10px] font-bold uppercase ring-1 ring-inset',
										methodStyle(op.method ?? op.action)
									)}
								>
									{pill}
								</span>
							{/if}
							<span class="truncate font-mono text-xs">{op.name}</span>
						</label>
					{/each}
				</div>
			{/if}
		</Card.Content>
		{/if}
	</Card.Root>

	<!-- ── Section 3: Artifacts & capabilities (hidden when none attached) ─── -->
	{#if showArtifacts}
		<Card.Root>
			{@render sectionHead(
				'3. Artifacts & capabilities',
				'Select which attached artifacts this plan exposes.',
				openArtifacts,
				() => (openArtifacts = !openArtifacts),
				artifactsSummary
			)}
			{#if openArtifacts}
				<Card.Content class="space-y-4">
			{#if loading}
				<p class="text-muted-foreground text-sm">Loading artifacts…</p>
			{:else if totalArtifacts === 0}
				<p class="text-muted-foreground text-sm">
					No custom artifacts attached to this service. Attach artifacts from the
					<a href="/services/{serviceId}/artifacts" class="text-primary hover:underline">Artifacts</a>
					tab.
				</p>
			{:else}
				<div class="grid gap-4 sm:grid-cols-2">
					{#each artifactGroups as group (group.type)}
						{@const Icon = CAPABILITY_ICONS[group.type]}
						<div class="flex flex-col gap-3 rounded-xl border p-4">
							<div class="flex items-center gap-2">
								{#if Icon}
									<HugeiconsIcon icon={Icon} size={16} class="text-muted-foreground shrink-0" />
								{/if}
								<h4 class="font-semibold">{group.label}</h4>
								<span class="text-muted-foreground text-xs">{group.items.length}</span>
							</div>
							<div class="flex flex-col gap-2">
								{#each group.items as art (art.id)}
									<div class="space-y-1.5">
										<label class="flex items-center gap-2 text-sm">
											<Checkbox
												checked={includedArtifacts.includes(art.name)}
												disabled={loading}
												onCheckedChange={(v) => toggleArtifact(art.name, v === true)}
											/>
											<span class="font-mono text-xs">{art.name}</span>
										</label>
										{#if art.capabilities.length}
											<div class="ms-6 flex flex-wrap gap-1">
												{#each art.capabilities as cap (cap)}
													<span
														class={cn(
															'inline-flex items-center rounded px-1.5 py-0.5 font-mono text-[10px] ring-1 ring-inset',
															CAPABILITY_STYLES[group.type] ??
																'bg-muted text-muted-foreground ring-border'
														)}
													>
														{cap}
													</span>
												{/each}
											</div>
										{/if}
									</div>
								{/each}
							</div>
						</div>
					{/each}
				</div>
			{/if}
		</Card.Content>
		{/if}
	</Card.Root>
	{/if}

	<!-- ── Section 4: MCP endpoint authentication ──────────────────────────── -->
	<Card.Root>
		{@render sectionHead(
			`${mcpSectionNo}. MCP endpoint authentication`,
			'Choose how MCP clients authenticate against the exposed endpoint.',
			openMcp,
			() => (openMcp = !openMcp),
			mcpSummary
		)}
		{#if openMcp}
			<Card.Content class="space-y-4">
			<div class="flex flex-wrap gap-2">
				<Button
					type="button"
					variant={mcpAuthMode === 'none' ? 'default' : 'outline'}
					size="sm"
					disabled={loading}
					onclick={() => (mcpAuthMode = 'none')}
				>
					None
				</Button>
				<Button
					type="button"
					variant={mcpAuthMode === 'apikey' ? 'default' : 'outline'}
					size="sm"
					disabled={loading}
					onclick={() => (mcpAuthMode = 'apikey')}
				>
					API Key
				</Button>
				<Button
					type="button"
					variant={mcpAuthMode === 'oauth' ? 'default' : 'outline'}
					size="sm"
					disabled={loading}
					onclick={() => (mcpAuthMode = 'oauth')}
				>
					OAuth
				</Button>
			</div>

			{#if mcpAuthMode === 'none'}
				<p class="text-muted-foreground text-sm">
					The MCP endpoint is publicly reachable without authentication.
				</p>
			{:else if mcpAuthMode === 'apikey'}
				<p class="text-muted-foreground text-sm">
					{#if isEdit}
						This plan is protected by an API key. Use <strong>Renew API key</strong> above to rotate it.
					{:else}
						An API key will be generated on creation and shown once — copy it immediately.
					{/if}
				</p>
			{:else}
				<p class="text-muted-foreground text-sm">
					Delegate authentication to one or more external OAuth2 authorization servers.
				</p>
				<div class="space-y-2">
					<Label for="oauthAuthServers">Authorization servers</Label>
					<Textarea
						id="oauthAuthServers"
						bind:value={oauthAuthServersText}
						rows={3}
						disabled={loading}
						placeholder={'https://auth.example.com/realms/main'}
					/>
					<p class="text-muted-foreground text-xs">One issuer URL per line.</p>
				</div>
				<div class="space-y-2">
					<Label for="oauthJwksUri">JWKS URI</Label>
					<Input
						id="oauthJwksUri"
						bind:value={oauthJwksUri}
						class="w-full"
						disabled={loading}
						placeholder="https://auth.example.com/realms/main/protocol/openid-connect/certs"
					/>
				</div>
				<div class="space-y-2">
					<Label for="oauthScopes">Scopes</Label>
					<Textarea
						id="oauthScopes"
						bind:value={oauthScopesText}
						rows={3}
						disabled={loading}
						placeholder={'openid\nprofile\nmcp:invoke'}
					/>
					<p class="text-muted-foreground text-xs">One scope per line.</p>
				</div>
			{/if}
		</Card.Content>
		{/if}
	</Card.Root>

	<!-- ── Section 5: Backend authentication ───────────────────────────────── -->
	<Card.Root>
		{@render sectionHead(
			`${backendSectionNo}. Backend authentication`,
			'Authenticate calls to the backend API with an endpoint secret.',
			openBackend,
			() => (openBackend = !openBackend),
			backendSummary
		)}
		{#if openBackend}
			<Card.Content class="space-y-2">
				<Label for="backendSecret">Backend secret</Label>
				<Select.Root
					type="single"
					value={backendSecretId || NONE_SECRET}
					onValueChange={onSecretChange}
				>
					<Select.Trigger id="backendSecret" class="w-full sm:w-96" disabled={loading}>
						{selectedSecretLabel}
					</Select.Trigger>
					<Select.Content>
						<Select.Item value={NONE_SECRET}>None (public backend)</Select.Item>
						{#each secrets as s (s.id)}
							<Select.Item value={s.id}>{s.name}</Select.Item>
						{/each}
					</Select.Content>
				</Select.Root>
				{#if secrets.length === 0 && !loading}
					<p class="text-muted-foreground text-xs">
						No endpoint secret found. Create one of type <code class="text-xs">ENDPOINT</code> on the
						Secrets page.
					</p>
				{:else}
					<p class="text-muted-foreground text-xs">
						Manage secrets on the
						<a href="/secrets" class="text-primary hover:underline">Secrets</a> page.
					</p>
				{/if}
			</Card.Content>
		{/if}
	</Card.Root>

	<div class="flex justify-end gap-2">
		<Button type="submit" disabled={loading || saving}>
			{saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create plan'}
		</Button>
	</div>
</form>

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete configuration plan"
	description={`You are about to delete this configuration plan. This action cannot be undone.`}
	confirmLabel="Delete"
	onConfirm={confirmDeletePlan}
>
	<p class="text-muted-foreground text-sm">
		The MCP endpoint exposed by this plan will no longer be served. Any client connected through it
		will lose access until another plan is configured.
	</p>
</ConfirmDialog>

















