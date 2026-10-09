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
	import { quotaEntry } from '$lib/dashboardStatsCompute.js';
	import { avatarColor } from '$lib/avatarColor.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import OrganizationBadge from '$lib/components/OrganizationBadge.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import QuotaGauge from '$lib/components/QuotaGauge.svelte';
	import { auth } from '$lib/stores/auth.svelte.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
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
		RefreshIcon,
		Search01Icon,
		MoreVerticalIcon,
		PencilEdit02Icon,
		Delete02Icon,
		PlusSignIcon
	} from '@hugeicons/core-free-icons';

	const QUOTA_METRIC = 'gateway-group.count';

	type GatewayGroup = {
		id?: string;
		organizationId?: string;
		name?: string;
		labels?: Record<string, string>;
	};

	type QuotaInfo = { used: number; limit: number; remaining: number } | null;

	// ── List state ────────────────────────────────────────────
	let rows = $state<GatewayGroup[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let query = $state('');

	// ── Quota state ───────────────────────────────────────────
	let quota = $state<QuotaInfo>(null);
	// When no quota entry is returned, creation is not restricted.
	const canCreate = $derived(quota === null || quota.remaining > 0);


	const filtered = $derived(
		query.trim() === ''
			? rows
			: rows.filter((r) => (r.name ?? '').toLowerCase().includes(query.trim().toLowerCase()))
	);

	function asRows(data: unknown[]): GatewayGroup[] {
		return data.filter((r): r is GatewayGroup => r !== null && typeof r === 'object');
	}

	function labelEntries(labels: Record<string, string> | undefined): [string, string][] {
		if (!labels || typeof labels !== 'object') return [];
		return Object.entries(labels);
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
			const [data] = await Promise.all([apiClient().listGatewayGroups(), refreshQuota()]);
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

	// ── Create / Edit drawer state ────────────────────────────
	let drawerOpen = $state(false);
	let editingId = $state<string | null>(null);
	let editingOrgId = $state<string | undefined>(undefined);
	let submitting = $state(false);
	let formError = $state('');

	// Form fields
	let fName = $state('');
	let labelRows = $state<{ key: string; value: string }[]>([]);

	// Work around a bits-ui body-scroll-lock issue (see secrets page): force-unfreeze
	// the body after the dialog close restore window whenever the drawer is closed.
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
		editingId = null;
		editingOrgId = undefined;
		fName = '';
		labelRows = [];
		formError = '';
	}

	// Force a clean open transition so the drawer reliably reopens even if a
	// previous user-initiated close left `drawerOpen` out of sync.
	async function openDrawer() {
		drawerOpen = false;
		await tick();
		drawerOpen = true;
	}

	function openCreate() {
		if (!canCreate) return;
		resetForm();
		void openDrawer();
	}

	async function openEdit(row: GatewayGroup) {
		resetForm();
		await openDrawer();
		if (!row.id) return;
		editingId = row.id;
		editingOrgId = row.organizationId;
		fName = row.name ?? '';
		labelRows = labelEntries(row.labels).map(([key, value]) => ({ key, value }));
	}

	function addLabelRow() {
		labelRows = [...labelRows, { key: '', value: '' }];
	}

	function removeLabelRow(index: number) {
		labelRows = labelRows.filter((_, i) => i !== index);
	}

	function buildBody(): Record<string, unknown> {
		const labels: Record<string, string> = {};
		for (const { key, value } of labelRows) {
			const k = key.trim();
			if (k) labels[k] = value;
		}
		const body: Record<string, unknown> = { name: fName.trim(), labels };
		if (editingId) {
			body.id = editingId;
			if (editingOrgId) body.organizationId = editingOrgId;
		}
		return body;
	}

	async function handleSubmit(e: Event) {
		e.preventDefault();
		formError = '';
		submitting = true;
		try {
			const body = buildBody();
			if (editingId) {
				await apiClient().updateGatewayGroup(editingId, body);
			} else {
				await apiClient().createGatewayGroup(body);
			}
			drawerOpen = false;
			// Reload the list and refresh quotas (creation consumes a quota unit).
			await load();
		} catch (e) {
			formError = formatApiError(e);
		} finally {
			submitting = false;
		}
	}

	let deleteTarget = $state<GatewayGroup | null>(null);
	let deleteOpen = $state(false);

	function onDelete(row: GatewayGroup) {
		deleteTarget = row;
		deleteOpen = true;
	}

	async function confirmDeleteGroup() {
		const row = deleteTarget;
		if (!row?.id) return;
		await apiClient().deleteGatewayGroup(row.id);
		// Reload the list and refresh quotas (deletion releases a quota unit).
		await load();
	}
</script>

<svelte:head>
	<title>Gateway groups — reShapr</title>
</svelte:head>

<PageHeader
	title="Gateway groups"
	subtitle="Logical groups of gateways used to expose your MCP servers."
>
	{#snippet actions()}
		<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" disabled={loading} onclick={() => void load()}>
			<HugeiconsIcon icon={RefreshIcon} size={16} />
		</Button>
		<Button onclick={openCreate} disabled={!canCreate} title={canCreate ? undefined : 'Quota reached'}>
			New group
		</Button>
	{/snippet}
</PageHeader>

{#if error}
	<div class="mb-4">
		<ApiErrorAlert message={error} />
	</div>
{/if}

{#if quota}
	<div class="mb-4">
		<QuotaGauge
			{quota}
			label="Gateway group quota"
			fullMessage="Quota reached. Delete an existing group to create a new one."
		/>
	</div>
{/if}

<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
	<div class="flex items-baseline gap-2">
		<h3 class="text-base font-semibold">All gateway groups</h3>
		{#if !loading}
			<span class="text-muted-foreground text-sm">
				{#if query.trim()}
					{filtered.length} / {rows.length}
				{:else}
					{rows.length} group{rows.length === 1 ? '' : 's'}
				{/if}
			</span>
		{/if}
	</div>
	{#if !loading && rows.length > 0}
		<div class="relative w-full sm:w-64">
			<HugeiconsIcon
			icon={Search01Icon}
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
		<p class="text-sm">No gateway group yet.</p>
		<Button class="mt-3" size="sm" onclick={openCreate} disabled={!canCreate}>
			Create your first gateway group
		</Button>
	</div>
{:else if filtered.length === 0}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No gateway group matches “{query}”.</p>
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
					<Table.Head>Labels</Table.Head>
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
						<Table.Cell>
							{@const entries = labelEntries(row.labels)}
							{#if entries.length === 0}
								<span class="text-muted-foreground">—</span>
							{:else}
								<div class="flex flex-wrap gap-1">
									{#each entries as [k, v] (k)}
										<Badge
											class="border-transparent font-mono text-xs text-white"
											style="background-color: {avatarColor(k)};"
										>
											{k}={v}
										</Badge>
									{/each}
								</div>
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
									<DropdownMenuItem class="px-4" onclick={() => void openEdit(row)}>
										<HugeiconsIcon icon={PencilEdit02Icon} size={16} />
										Edit
									</DropdownMenuItem>
									<DropdownMenuItem class="text-destructive px-4" onclick={() => void onDelete(row)}>
										<HugeiconsIcon icon={Delete02Icon} size={16} />
										Delete
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
<!-- Create / Edit Gateway Group Drawer                          -->
<!-- ═══════════════════════════════════════════════════════════ -->
<Sheet bind:open={drawerOpen}>
	<SheetContent side="right" class="flex flex-col sm:max-w-lg">
		<SheetHeader>
			<SheetTitle>{editingId ? 'Edit gateway group' : 'Create gateway group'}</SheetTitle>
			<SheetDescription>
				{editingId
					? 'Update the configuration of this gateway group.'
					: 'Register a new gateway group in the reShapr control plane.'}
			</SheetDescription>
		</SheetHeader>

		<form onsubmit={handleSubmit} class="flex-1 space-y-4 overflow-y-auto px-4">
			<div class="space-y-2">
				<Label for="ggName">Name <span class="text-destructive">*</span></Label>
				<Input id="ggName" placeholder="my-gateway-group" bind:value={fName} required />
			</div>

			<div class="space-y-2">
				<div class="flex items-center justify-between">
					<Label>Labels</Label>
					<Button type="button" variant="outline" size="sm" onclick={addLabelRow}>
						<HugeiconsIcon icon={PlusSignIcon} size={16} />
						Add label
					</Button>
				</div>
				{#if labelRows.length === 0}
					<p class="text-muted-foreground text-xs">No labels. Add key/value pairs to tag this group.</p>
				{:else}
					<div class="space-y-2">
						{#each labelRows as label, i (i)}
							<div class="flex items-center gap-2">
								<Input placeholder="key" bind:value={label.key} class="font-mono" />
								<span class="text-muted-foreground">=</span>
								<Input placeholder="value" bind:value={label.value} class="font-mono" />
								<Button
									type="button"
									variant="ghost"
									size="icon"
									onclick={() => removeLabelRow(i)}
									aria-label="Remove label"
								>
									<Trash2Icon class="size-4" />
								</Button>
							</div>
						{/each}
					</div>
				{/if}
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
						{editingId ? 'Saving…' : 'Creating…'}
					{:else}
						{editingId ? 'Save changes' : 'Create group'}
					{/if}
				</Button>
			</SheetFooter>
		</form>
	</SheetContent>
</Sheet>

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete gateway group"
	description={deleteTarget
		? `You are about to delete the gateway group "${deleteTarget.name ?? deleteTarget.id}". This action cannot be undone.`
		: undefined}
	confirmLabel="Delete"
	onConfirm={confirmDeleteGroup}
>
	<p class="text-muted-foreground text-sm">
		Gateways that belong to this group will lose their group assignment, and any exposition routed
		through it may become unreachable until reassigned.
	</p>
</ConfirmDialog>

