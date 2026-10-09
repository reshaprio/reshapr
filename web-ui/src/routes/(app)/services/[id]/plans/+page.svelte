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
	import { getContext } from 'svelte';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { planBelongsToService } from '$lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import {
		DropdownMenu,
		DropdownMenuContent,
		DropdownMenuItem,
		DropdownMenuTrigger
	} from '$lib/components/ui/dropdown-menu/index.js';
	import { cn } from '$lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
	MoreVerticalIcon,
	PencilEdit02Icon,
	Delete02Icon,
	RefreshIcon,
	Copy01Icon,
	Tick02Icon,
	UserLock01Icon,
	Key01Icon,
	MessageLock02Icon
	} from '@hugeicons/core-free-icons';
	import * as Dialog from '$lib/components/ui/dialog/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	type McpAuth = 'none' | 'apikey' | 'oauth';

	type PlanRow = {
		id: string;
		name: string;
		backendEndpoint: string;
		includedOps: number;
		excludedOps: number;
		includedArtifacts: number;
		mcpAuth: McpAuth;
		backendSecretId: string | null;
	};

	// Color-coded pill per MCP endpoint authentication method.
	const MCP_AUTH_META: Record<McpAuth, { label: string; classes: string }> = {
		none: { label: 'None', classes: 'bg-muted text-muted-foreground ring-border' },
		apikey: {
			label: 'API Key',
			classes: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
		},
		oauth: {
			label: 'OAuth',
			classes: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400'
		}
	};

	// Backend secret classification, mirroring the Secrets list "Credential" column.
	type SecretInfo = {
		name: string;
		username?: string;
		password?: string;
		token?: string;
		tokenHeader?: string;
		useElicitation?: boolean | string;
	};

	type CredKind = 'basic' | 'token' | 'elicitation' | 'unknown';

	function isElicitation(v: unknown): boolean {
		return v === true || v === 'true';
	}

	function credentialKind(s: SecretInfo): CredKind {
		if (isElicitation(s.useElicitation)) return 'elicitation';
		if ((s.username ?? '').trim() || (s.password ?? '').trim()) return 'basic';
		if ((s.token ?? '').trim() || (s.tokenHeader ?? '').trim()) return 'token';
		return 'unknown';
	}

	// eslint-disable-next-line @typescript-eslint/no-explicit-any
	const CRED_META: Record<CredKind, { label: string; classes: string; icon: any }> = {
	basic: {
		label: 'User / password',
		icon: UserLock01Icon,
		classes: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400'
	},
	token: {
		label: 'Token',
		icon: Key01Icon,
		classes: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
	},
	elicitation: {
		label: 'Elicitation',
		icon: MessageLock02Icon,
		classes: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400'
	},
		unknown: {
			label: 'Other',
			icon: Key01Icon,
			classes: 'bg-muted text-muted-foreground ring-border'
		}
	};

	let rows = $state<PlanRow[]>([]);
	/** Map of secret id → secret info, used to render the backend authentication column. */
	let secretsById = $state<Record<string, SecretInfo>>({});
	let error = $state<string | null>(null);
	let loading = $state(true);

	function count(v: unknown): number {
		return Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim() !== '').length : 0;
	}

	function mcpAuthOf(o: Record<string, unknown>): McpAuth {
		if (o.oauth2Configuration && typeof o.oauth2Configuration === 'object') return 'oauth';
		if (typeof o.apiKey === 'string' && o.apiKey.trim() !== '') return 'apikey';
		return 'none';
	}

	function toRow(raw: unknown): PlanRow | null {
		if (!raw || typeof raw !== 'object') return null;
		const o = raw as Record<string, unknown>;
		if (typeof o.id !== 'string') return null;
		return {
			id: o.id,
			name: typeof o.name === 'string' ? o.name : '—',
			backendEndpoint: typeof o.backendEndpoint === 'string' ? o.backendEndpoint : '—',
			includedOps: count(o.includedOperations),
			excludedOps: count(o.excludedOperations),
			includedArtifacts: count(o.includedArtifacts),
			mcpAuth: mcpAuthOf(o),
			backendSecretId: typeof o.backendSecretId === 'string' ? o.backendSecretId : null
		};
	}

	function opsSummary(row: PlanRow): string {
		if (row.includedOps > 0) return `${row.includedOps} operations included`;
		if (row.excludedOps > 0) return `${row.excludedOps} operations excluded`;
		return 'All operations';
	}

	function artifactsSummary(row: PlanRow): string {
		if (row.includedArtifacts > 0)
			return `${row.includedArtifacts} artifact${row.includedArtifacts === 1 ? '' : 's'}`;
		return 'All artifacts';
	}

	function backendSecretOf(row: PlanRow): SecretInfo | null {
		if (!row.backendSecretId) return null;
		return secretsById[row.backendSecretId] ?? { name: row.backendSecretId };
	}

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		try {
			const [all, secretList] = await Promise.all([
				apiClient().listConfigurationPlans(),
				apiClient()
					.listSecrets()
					.catch(() => [] as unknown[])
			]);
			secretsById = Object.fromEntries(
				(Array.isArray(secretList) ? secretList : [])
					.map((s) => (s && typeof s === 'object' ? (s as Record<string, unknown>) : null))
					.filter((s): s is Record<string, unknown> => !!s && typeof s.id === 'string')
					.map((s) => [
						s.id as string,
						{
							name: typeof s.name === 'string' ? s.name : (s.id as string),
							username: typeof s.username === 'string' ? s.username : undefined,
							password: typeof s.password === 'string' ? s.password : undefined,
							token: typeof s.token === 'string' ? s.token : undefined,
							tokenHeader: typeof s.tokenHeader === 'string' ? s.tokenHeader : undefined,
							useElicitation: s.useElicitation as boolean | string | undefined
						} satisfies SecretInfo
					])
			);
			const list = (Array.isArray(all) ? all : []).filter((p) => planBelongsToService(p, ctx.id));
			rows = list.map(toRow).filter((r): r is PlanRow => r != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			rows = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading) void load();
	});

	let deleteTarget = $state<PlanRow | null>(null);
	let deleteOpen = $state(false);

	let duplicateTarget = $state<PlanRow | null>(null);
	let duplicateOpen = $state(false);
	let duplicateNewName = $state('');
	let duplicateBusy = $state(false);
	let duplicateError = $state<string | null>(null);
	let duplicateSuccess = $state(false);
	let duplicatedApiKey = $state<string | null>(null);
	let duplicatedInitialToken = $state<string | null>(null);
	let copiedField = $state<'apiKey' | 'initialToken' | null>(null);

	async function copyValue(field: 'apiKey' | 'initialToken', value: string | null) {
		if (!value) return;
		try {
			await navigator.clipboard.writeText(value);
			copiedField = field;
			setTimeout(() => {
				if (copiedField === field) copiedField = null;
			}, 1500);
		} catch {
			// clipboard denied, ignore
		}
	}

	function onDelete(row: PlanRow) {
		deleteTarget = row;
		deleteOpen = true;
	}

	function onDuplicate(row: PlanRow) {
		duplicateTarget = row;
		duplicateNewName = `${row.name} (Copy)`;
		duplicateError = null;
		duplicateSuccess = false;
		duplicatedApiKey = null;
		duplicatedInitialToken = null;
		duplicateOpen = true;
	}

	async function confirmDeletePlan() {
		const row = deleteTarget;
		if (!row) return;
		await apiClient().deleteConfigurationPlan(row.id);
		await load();
	}

	async function confirmDuplicatePlan(e: Event) {
		e.preventDefault();
		const row = duplicateTarget;
		if (!row || !duplicateNewName.trim()) return;
		duplicateBusy = true;
		duplicateError = null;
		try {
			const res = await apiClient().duplicateConfigurationPlan(row.id, duplicateNewName.trim()) as { apiKey?: string; initialAccessToken?: string };
			await load();
			
			if (res.apiKey || res.initialAccessToken) {
				duplicatedApiKey = res.apiKey || null;
				duplicatedInitialToken = res.initialAccessToken || null;
				duplicateSuccess = true;
			} else {
				duplicateOpen = false;
			}
		} catch (err) {
			duplicateError = err instanceof ApiError ? err.message : String(err);
		} finally {
			duplicateBusy = false;
		}
	}
</script>

<div class="mb-4 flex flex-wrap items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">Configuration plans</h3>
	<div class="flex gap-2">
		<Button variant="outline" size="icon-sm" title="Refresh" aria-label="Refresh" disabled={loading} onclick={() => void load()}>
			<HugeiconsIcon icon={RefreshIcon} size={14} />
		</Button>
		<Button size="sm" href="/services/{ctx.id}/plans/new">New plan</Button>
	</div>
</div>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>Name</Table.Head>
				<Table.Head>Backend</Table.Head>
				<Table.Head>Scope</Table.Head>
				<Table.Head>MCP auth</Table.Head>
				<Table.Head>Backend auth</Table.Head>
				<Table.Head class="w-16 text-right">Actions</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#if loading}
				<Table.Row>
					<Table.Cell colspan={6} class="text-muted-foreground">Loading…</Table.Cell>
				</Table.Row>
			{:else if rows.length === 0}
				<Table.Row>
					<Table.Cell colspan={6} class="text-muted-foreground">
						No plans for this service. <a
							href="/services/{ctx.id}/plans/new"
							class="text-primary hover:underline">Create one</a
						>.
					</Table.Cell>
				</Table.Row>
			{:else}
				{#each rows as p (p.id)}
					{@const mcp = MCP_AUTH_META[p.mcpAuth]}
					{@const secret = backendSecretOf(p)}
					<Table.Row>
						<Table.Cell class="font-medium">
							<div class="flex flex-col gap-1">
								<span>{p.name}</span>
								<code
									class="text-muted-foreground bg-muted w-fit rounded px-1 py-0.5 font-mono text-xs break-all"
									>{p.id}</code
								>
							</div>
						</Table.Cell>
						<Table.Cell class="max-w-xs truncate" title={p.backendEndpoint}>
							<code class="truncate rounded px-1.5 py-0.5 font-mono text-xs">{p.backendEndpoint}</code>
						</Table.Cell>
						<Table.Cell>
							<div class="flex flex-col gap-0.5 text-xs">
								<span>{opsSummary(p)}</span>
								<span class="text-muted-foreground">{artifactsSummary(p)}</span>
							</div>
						</Table.Cell>
						<Table.Cell>
							<span
								class={cn(
									'inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
									mcp.classes
								)}
							>
								{mcp.label}
							</span>
						</Table.Cell>
						<Table.Cell>
							{#if secret}
								{@const meta = CRED_META[credentialKind(secret)]}
								{@const Icon = meta.icon}
								<div class="flex flex-col gap-1">
									<span
										class={cn(
											'inline-flex w-fit items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
											meta.classes
										)}
									>
										<HugeiconsIcon icon={meta.icon} size={14} />
										{meta.label}
									</span>
									{#if secret.useElicitation}
										<span class="text-muted-foreground text-xs">Elicitation enabled</span>
									{/if}
								</div>
							{:else}
								<span
									class={cn(
										'inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
										'bg-muted text-muted-foreground ring-border'
									)}
								>
									None
								</span>
							{/if}
						</Table.Cell>
						<Table.Cell class="text-right">
							<DropdownMenu>
								<DropdownMenuTrigger>
									{#snippet child({ props })}
										<Button variant="ghost" size="icon" {...props}>
											<HugeiconsIcon icon={MoreVerticalIcon} size={16} />
										</Button>
									{/snippet}
								</DropdownMenuTrigger>
								<DropdownMenuContent align="end">
									<DropdownMenuItem class="cursor-pointer">
										{#snippet child({ props })}
											<a href="/services/{ctx.id}/plans/{p.id}" class="px-4" {...props}>
												<HugeiconsIcon icon={PencilEdit02Icon} size={16} />
												Edit
											</a>
										{/snippet}
									</DropdownMenuItem>
									<DropdownMenuItem
										class="cursor-pointer"
										onSelect={() => void onDuplicate(p)}
									>
										<HugeiconsIcon icon={Copy01Icon} size={16} />
										Duplicate
									</DropdownMenuItem>
									<DropdownMenuItem
										class="text-destructive focus:text-destructive cursor-pointer"
										onSelect={() => void onDelete(p)}
									>
										<HugeiconsIcon icon={Delete02Icon} size={16} />
										Delete
									</DropdownMenuItem>
								</DropdownMenuContent>
							</DropdownMenu>
						</Table.Cell>
					</Table.Row>
				{/each}
			{/if}
		</Table.Body>
	</Table.Root>
</div>

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete configuration plan"
	description={deleteTarget
		? `You are about to delete the configuration plan "${deleteTarget.name}". This action cannot be undone.`
		: undefined}
	confirmLabel="Delete"
	onConfirm={confirmDeletePlan}
>
	<p class="text-muted-foreground text-sm">
		The MCP endpoint exposed by this plan will no longer be served. Any client connected through it
		will lose access until another plan is configured.
	</p>
</ConfirmDialog>

<Dialog.Root bind:open={duplicateOpen} onOpenChange={(open) => { if (!open) { duplicateSuccess = false; duplicatedApiKey = null; duplicatedInitialToken = null; copiedField = null; } }}>
	<Dialog.Content>
		{#if duplicateSuccess}
			<Dialog.Header>
				<Dialog.Title>Configuration plan duplicated</Dialog.Title>
				<Dialog.Description>
					The plan "{duplicateNewName}" has been created successfully.
				</Dialog.Description>
			</Dialog.Header>
			<div class="flex flex-col gap-4 py-2">
				{#if duplicatedApiKey}
					<div class="rounded-lg border border-amber-500/30 bg-amber-500/10 p-4">
						<div class="flex items-start justify-between gap-3">
							<div class="min-w-0">
								<p class="text-sm font-semibold text-amber-700 dark:text-amber-400">
									Copy your API key now
								</p>
								<p class="text-muted-foreground mt-0.5 text-xs">
									This is the only time the API key value will be shown.
								</p>
								<code class="mt-2 block font-mono text-xs break-all">{duplicatedApiKey}</code>
							</div>
							<div class="flex shrink-0 items-center gap-1">
								<Button
									variant="outline"
									size="sm"
									onclick={() => void copyValue('apiKey', duplicatedApiKey)}
								>
									{#if copiedField === 'apiKey'}
										<HugeiconsIcon icon={Tick02Icon} size={16} />
										Copied
									{:else}
										<HugeiconsIcon icon={Copy01Icon} size={16} />
										Copy
									{/if}
								</Button>
							</div>
						</div>
					</div>
				{/if}
				{#if duplicatedInitialToken}
					<div class="rounded-lg border border-amber-500/30 bg-amber-500/10 p-4">
						<div class="flex items-start justify-between gap-3">
							<div class="min-w-0">
								<p class="text-sm font-semibold text-amber-700 dark:text-amber-400">
									Copy your initial access token now
								</p>
								<p class="text-muted-foreground mt-0.5 text-xs">
									This is the only time the initial access token value will be shown.
								</p>
								<code class="mt-2 block font-mono text-xs break-all">{duplicatedInitialToken}</code>
							</div>
							<div class="flex shrink-0 items-center gap-1">
								<Button
									variant="outline"
									size="sm"
									onclick={() => void copyValue('initialToken', duplicatedInitialToken)}
								>
									{#if copiedField === 'initialToken'}
										<HugeiconsIcon icon={Tick02Icon} size={16} />
										Copied
									{:else}
										<HugeiconsIcon icon={Copy01Icon} size={16} />
										Copy
									{/if}
								</Button>
							</div>
						</div>
					</div>
				{/if}
			</div>
			<Dialog.Footer>
				<Button type="button" onclick={() => (duplicateOpen = false)}>
					Close
				</Button>
			</Dialog.Footer>
		{:else}
			<Dialog.Header>
				<Dialog.Title>Duplicate configuration plan</Dialog.Title>
				<Dialog.Description>
					Create a copy of "{duplicateTarget?.name}". A new API key will be generated if applicable.
				</Dialog.Description>
			</Dialog.Header>
			{#if duplicateError}
				<ApiErrorAlert message={duplicateError} />
			{/if}
			<form onsubmit={confirmDuplicatePlan} class="flex flex-col gap-4 py-2">
				<div class="flex flex-col gap-2">
					<Label for="duplicate-name">New plan name</Label>
					<Input
						id="duplicate-name"
						bind:value={duplicateNewName}
						placeholder="My duplicated plan"
						disabled={duplicateBusy}
						required
					/>
				</div>
				<Dialog.Footer>
					<Button
						type="button"
						variant="outline"
						disabled={duplicateBusy}
						onclick={() => (duplicateOpen = false)}
					>
						Cancel
					</Button>
					<Button type="submit" disabled={duplicateBusy || !duplicateNewName.trim()}>
						{duplicateBusy ? 'Duplicating…' : 'Duplicate'}
					</Button>
				</Dialog.Footer>
			</form>
		{/if}
	</Dialog.Content>
</Dialog.Root>

