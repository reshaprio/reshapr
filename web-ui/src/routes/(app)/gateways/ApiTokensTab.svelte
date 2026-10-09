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
	import { apiClient } from '$lib/api/client.js';
	import { formatApiError } from '$lib/format-api-error.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import OrganizationBadge from '$lib/components/OrganizationBadge.svelte';
	import { auth } from '$lib/stores/auth.svelte.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import * as Select from '$lib/components/ui/select/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import {
		Sheet,
		SheetContent,
		SheetHeader,
		SheetTitle,
		SheetDescription,
		SheetFooter,
		SheetClose
	} from '$lib/components/ui/sheet/index.js';
	import {
		DropdownMenu,
		DropdownMenuContent,
		DropdownMenuItem,
		DropdownMenuTrigger
	} from '$lib/components/ui/dropdown-menu/index.js';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		Search01Icon,
		MoreVerticalIcon,
		Delete02Icon,
		Copy01Icon,
		Tick02Icon,
		Cancel01Icon,
		UserIcon
	} from '@hugeicons/core-free-icons';

type ApiTokenRow = {
		id?: string;
		organizationId?: string;
		name?: string;
		token?: string;
		validUntil?: string;
		username?: string;
	};

	const VALIDITY = ['1', '7', '30', '90'] as const;

	function validityLabel(days: string): string {
		return `${days} day${Number(days) > 1 ? 's' : ''}`;
	}

	// ── List state ────────────────────────────────────────────
	let rows = $state<ApiTokenRow[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let query = $state('');

	const filtered = $derived(
		query.trim() === ''
			? rows
			: rows.filter((r) => (r.name ?? '').toLowerCase().includes(query.trim().toLowerCase()))
	);

	function asRows(data: unknown[]): ApiTokenRow[] {
		return data.filter((r): r is ApiTokenRow => r !== null && typeof r === 'object');
	}

	async function load() {
		loading = true;
		error = null;
		try {
			const data = await apiClient().listApiTokens();
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

	// Exposed so the parent page header can trigger a refresh.
	export function refresh() {
		void load();
	}

	// ── Created token banner (shown once) ─────────────────────
	let createdToken = $state<string | null>(null);
	let copied = $state(false);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;

	async function copyCreatedToken() {
		if (!createdToken) return;
		try {
			await navigator.clipboard.writeText(createdToken);
			copied = true;
			clearTimeout(copiedTimer);
			copiedTimer = setTimeout(() => (copied = false), 1500);
		} catch {
			// Clipboard may be unavailable (e.g. insecure context); ignore.
		}
	}

	// ── Create drawer state ───────────────────────────────────
	let drawerOpen = $state(false);
	let submitting = $state(false);
	let formError = $state('');

	// Form fields
	let fName = $state('');
	let fValidity = $state<string>('30');

	// Work around a bits-ui body-scroll-lock issue (see secrets/gateway-groups
	// pages): force-unfreeze the body after the dialog close restore window
	// whenever the drawer is closed.
	$effect(() => {
		if (drawerOpen) return;
		const unfreeze = () => {
			if (document.body.style.pointerEvents === 'none') {
				document.body.style.removeProperty('pointer-events');
				document.body.style.removeProperty('overflow');
			}
		};
		const timers = [50, 200].map((d) => setTimeout(unfreeze, d));
		return () => timers.forEach(clearTimeout);
	});

	function resetForm() {
		fName = '';
		fValidity = '30';
		formError = '';
	}

	// Force a clean open transition so the drawer reliably reopens even if a
	// previous user-initiated close left `drawerOpen` out of sync.
	async function openDrawer() {
		drawerOpen = false;
		await tick();
		drawerOpen = true;
	}

	// Exposed so the parent page header can open the create drawer.
	export function openCreateDrawer() {
		resetForm();
		void openDrawer();
	}

	async function handleSubmit(e: Event) {
		e.preventDefault();
		formError = '';
		submitting = true;
		try {
			const out = (await apiClient().createApiToken({
				name: fName.trim(),
				validityDays: fValidity
			})) as { token?: string };
			drawerOpen = false;
			createdToken = out.token ?? null;
			copied = false;
			await load();
		} catch (e) {
			formError = formatApiError(e);
		} finally {
			submitting = false;
		}
	}

	let deleteTarget = $state<ApiTokenRow | null>(null);
	let deleteOpen = $state(false);

	function onDelete(row: ApiTokenRow) {
		deleteTarget = row;
		deleteOpen = true;
	}

	async function confirmRevokeToken() {
		const row = deleteTarget;
		if (!row?.id) return;
		await apiClient().deleteApiToken(row.id);
		await load();
	}
</script>

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- API Tokens Tab Content                                      -->
<!-- ═══════════════════════════════════════════════════════════ -->
{#if error}
	<div class="mb-4">
		<ApiErrorAlert message={error} />
	</div>
{/if}

{#if createdToken}
	<div class="mb-4 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4">
		<div class="flex items-start justify-between gap-3">
			<div class="min-w-0">
				<p class="text-sm font-semibold text-amber-700 dark:text-amber-400">Copy your token now</p>
				<p class="text-muted-foreground mt-0.5 text-xs">
					This is the only time the token value will be shown.
				</p>
				<code class="mt-2 block font-mono text-xs break-all">{createdToken}</code>
			</div>
			<div class="flex shrink-0 items-center gap-1">
				<Button variant="outline" size="sm" onclick={() => void copyCreatedToken()}>
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
					onclick={() => (createdToken = null)}
					aria-label="Dismiss"
				>
					<HugeiconsIcon icon={Cancel01Icon} size={16} />
				</Button>
			</div>
		</div>
	</div>
{/if}

<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
	<div class="flex items-baseline gap-2">
		<h3 class="text-base font-semibold">All API tokens</h3>
		{#if !loading}
			<span class="text-muted-foreground text-sm">
				{#if query.trim()}
					{filtered.length} / {rows.length}
				{:else}
					{rows.length} token{rows.length === 1 ? '' : 's'}
				{/if}
			</span>
		{/if}
	</div>
	{#if !loading && rows.length > 0}
		<div class="relative w-full sm:w-64">
			<HugeiconsIcon icon={Search01Icon} size={16}
				class="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2"
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
		<p class="text-sm">No API token yet.</p>
		<Button class="mt-3" size="sm" onclick={openCreateDrawer}>Create your first API token</Button>
	</div>
{:else if filtered.length === 0}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No API token matches “{query}”.</p>
	</div>
{:else}
	<div class="rounded-lg border">
		<Table.Root>
			<Table.Header>
				<Table.Row>
					<Table.Head>Name</Table.Head>
					{#if auth.isAdmin}
						<Table.Head>Org</Table.Head>
					{/if}
					<Table.Head>Issued by</Table.Head>
					<Table.Head>Valid until</Table.Head>
					<Table.Head class="w-16 text-right">Actions</Table.Head>
				</Table.Row>
			</Table.Header>
			<Table.Body>
				{#each filtered as row (row.id ?? `${row.name}-${row.organizationId}`)}
					<Table.Row>
						<Table.Cell class="font-medium">
							<div class="flex flex-col gap-1">
								<span>{row.name ?? '—'}</span>
								<code
									class="text-muted-foreground bg-muted w-fit rounded px-1 py-0.5 font-mono text-xs break-all"
									>{row.id ?? '—'}</code
								>
							</div>
						</Table.Cell>
						{#if auth.isAdmin}
							<Table.Cell>
								{#if row.organizationId}
									<OrganizationBadge organizationName={row.organizationId} />
								{:else}
									—
								{/if}
							</Table.Cell>
						{/if}
						<Table.Cell class="text-muted-foreground">
							{#if row.username}
								<Badge variant="secondary">
									<HugeiconsIcon icon={UserIcon} size={12} class="mr-1" />
									{row.username}
								</Badge>
							{:else}
								—
							{/if}
						</Table.Cell>
						<Table.Cell>
							{#if row.validUntil}
								<Badge variant="secondary" class="font-mono text-xs">
									{new Date(row.validUntil).toLocaleString()}
								</Badge>
							{:else}
								<span class="text-muted-foreground">—</span>
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
									<DropdownMenuItem
										class="text-destructive px-4"
										onclick={() => void onDelete(row)}
									>
										<HugeiconsIcon icon={Delete02Icon} size={16} />
										Revoke
									</DropdownMenuItem>
								</DropdownMenuContent>
							</DropdownMenu>
						</Table.Cell>
					</Table.Row>
				{/each}
			</Table.Body>
		</Table.Root>
	</div>
{/if}

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Create API Token Drawer                                     -->
<!-- ═══════════════════════════════════════════════════════════ -->
<Sheet bind:open={drawerOpen}>
	<SheetContent side="right" class="flex flex-col sm:max-w-lg">
		<SheetHeader>
			<SheetTitle>Create API token</SheetTitle>
			<SheetDescription>
				Generate a new API token in the reShapr control plane. The token value is shown only once
				after creation.
			</SheetDescription>
		</SheetHeader>

		<form onsubmit={handleSubmit} class="flex-1 space-y-4 overflow-y-auto px-4">
			<div class="space-y-2">
				<Label for="tokenName">Name <span class="text-destructive">*</span></Label>
				<Input id="tokenName" placeholder="my-api-token" bind:value={fName} required />
			</div>

			<div class="space-y-2">
				<Label for="tokenValidity">Validity</Label>
				<Select.Root type="single" bind:value={fValidity}>
					<Select.Trigger id="tokenValidity" class="w-full">
						{validityLabel(fValidity)}
					</Select.Trigger>
					<Select.Content>
						{#each VALIDITY as d (d)}
							<Select.Item value={d}>{validityLabel(d)}</Select.Item>
						{/each}
					</Select.Content>
				</Select.Root>
				<p class="text-muted-foreground text-xs">
					The token will be valid for this number of days from today.
				</p>
			</div>

			{#if formError}
				<div class="bg-destructive/10 text-destructive rounded-md px-4 py-3 text-sm">
					{formError}
				</div>
			{/if}

			<SheetFooter class="pt-4">
				<SheetClose>
					{#snippet child({ props })}
						<Button variant="outline" type="button" {...props}>Cancel</Button>
					{/snippet}
				</SheetClose>
				<Button type="submit" disabled={submitting || !fName.trim()}>
					{#if submitting}
						<div
							class="border-primary-foreground h-4 w-4 animate-spin rounded-full border-2 border-t-transparent"
						></div>
						Creating…
					{:else}
						Create token
					{/if}
				</Button>
			</SheetFooter>
		</form>
	</SheetContent>
</Sheet>

<ConfirmDialog
	bind:open={deleteOpen}
	title="Revoke API token"
	description={deleteTarget
		? `You are about to revoke the token "${deleteTarget.name ?? deleteTarget.id}". This action cannot be undone.`
		: undefined}
	confirmLabel="Revoke"
	confirmingLabel="Revoking…"
	onConfirm={confirmRevokeToken}
>
	<p class="text-muted-foreground text-sm">
		Any client or gateway currently authenticating with this token will immediately stop working
		and will need to be reconfigured with a new token.
	</p>
</ConfirmDialog>

