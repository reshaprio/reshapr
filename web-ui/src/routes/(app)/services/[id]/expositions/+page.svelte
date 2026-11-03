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
	import { getContext, tick } from 'svelte';
	import { SvelteMap } from 'svelte/reactivity';
	import { apiClient, ApiError } from '#lib/api/client.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import ConfirmDialog from '#lib/components/ConfirmDialog.svelte';
	import CreateExpositionDrawer from '#lib/components/exposition/CreateExpositionDrawer.svelte';
	import { expositionBelongsToService } from '#lib/serviceHub.js';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '#lib/serviceContext.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { Badge } from '#lib/components/ui/badge/index.js';
	import { Switch } from '#lib/components/ui/switch/index.js';
	import { Label } from '#lib/components/ui/label/index.js';
	import * as Table from '#lib/components/ui/table/index.js';
	import {
		DropdownMenu,
		DropdownMenuContent,
		DropdownMenuItem,
		DropdownMenuTrigger
	} from '#lib/components/ui/dropdown-menu/index.js';
	import { cn } from '#lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { MoreVerticalIcon, Delete02Icon, TagsIcon, RefreshIcon } from '@hugeicons/core-free-icons';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	// Human-readable label for the (locked) service passed to the create wizard.
	const serviceLabel = $derived(
		ctx.service
			? ctx.service.version && ctx.service.version !== '—'
				? `${ctx.service.name}:${ctx.service.version}`
				: ctx.service.name
			: null
	);

	// ── Create drawer state ───────────────────────────────────
	let drawerOpen = $state(false);

	// Force a clean open transition so the drawer reliably reopens.
	async function openCreate() {
		drawerOpen = false;
		await tick();
		drawerOpen = true;
	}

	type ExpoRow = {
		id: string;
		name: string | null;
		active: boolean;
		planId: string | null;
		planName: string | null;
		gatewayGroupId: string | null;
		gatewayGroupName: string | null;
		backend: string;
	};

	// Color-coded pill per exposition status.
	const STATUS_META: Record<'active' | 'inactive', { label: string; classes: string }> = {
		active: {
			label: 'Active',
			classes: 'bg-primary/10 text-primary ring-primary/20'
		},
		inactive: {
			label: 'Inactive',
			classes: 'bg-muted text-muted-foreground ring-border'
		}
	};

	let rows = $state<ExpoRow[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);
	let mode = $state<'active' | 'all'>('active');

	function asRecord(raw: unknown): Record<string, unknown> | null {
		return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : null;
	}

	function backendUrl(configurationPlan: unknown): string {
		const c = asRecord(configurationPlan);
		if (!c) return '—';
		return typeof c.backendEndpoint === 'string' ? c.backendEndpoint : '—';
	}

	function expositionId(raw: unknown): string | null {
		const o = asRecord(raw);
		return o && typeof o.id === 'string' ? o.id : null;
	}

	function toRow(raw: unknown, active: boolean): ExpoRow | null {
		const o = asRecord(raw);
		if (!o || typeof o.id !== 'string') return null;
		const plan = asRecord(o.configurationPlan);
		const group = asRecord(o.gatewayGroup);
		return {
			id: o.id,
			name: typeof o.name === 'string' && o.name.trim() ? o.name.trim() : null,
			active,
			planId: plan && typeof plan.id === 'string' ? plan.id : null,
			planName: plan && typeof plan.name === 'string' && plan.name.trim() ? plan.name.trim() : null,
			gatewayGroupId: group && typeof group.id === 'string' ? group.id : null,
			gatewayGroupName:
				group && typeof group.name === 'string' && group.name.trim() ? group.name.trim() : null,
			backend: backendUrl(o.configurationPlan)
		};
	}

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		try {
			const c = apiClient();
			// Load both lists so we can display the Active/Inactive status per row,
			// even when browsing all expositions.
			const [allRaw, activeRaw] = await Promise.all([
				c.listExpositionsAll(),
				c.listExpositionsActive()
			]);

			const activeForService = (Array.isArray(activeRaw) ? activeRaw : []).filter((e) =>
				expositionBelongsToService(e, ctx.id),
			);
			const activeIds = new Set(
				activeForService.map((e) => expositionId(e)).filter((id): id is string => id != null),
			);
			const allForService = (Array.isArray(allRaw) ? allRaw : []).filter((e) =>
				expositionBelongsToService(e, ctx.id),
			);

			// The active exposition payload doesn't carry configurationPlan.name /
			// gatewayGroup.name, so index the full ("all") list by id and use it as the
			// source of truth for those details, cross-referencing by exposition id.
			const detailsById = new SvelteMap<string, unknown>();
			for (const e of allForService) {
				const id = expositionId(e);
				if (id) detailsById.set(id, e);
			}

			const source = mode === 'active' ? activeForService : allForService;
			rows = source
				.map((e) => {
					const id = expositionId(e);
					if (!id) return null;
					const detail = detailsById.get(id) ?? e;
					return toRow(detail, activeIds.has(id));
				})
				.filter((r): r is ExpoRow => r != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			rows = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void mode;
		if (ctx.id && !ctx.loading) void load();
	});

	let deleteTarget = $state<ExpoRow | null>(null);
	let deleteOpen = $state(false);

	function onDelete(row: ExpoRow) {
		deleteTarget = row;
		deleteOpen = true;
	}

	async function confirmDeleteExposition() {
		const row = deleteTarget;
		if (!row) return;
		await apiClient().deleteExposition(row.id);
		await load();
	}
</script>

<div class="mb-4 flex flex-wrap items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">Expositions</h3>
	<div class="flex flex-wrap items-center gap-2">
		<div class="flex items-center gap-2">
			<Label for="expo-mode-switch" class="text-muted-foreground text-sm">
				{mode === 'active' ? 'Active' : 'All'}
			</Label>
			<Switch
				id="expo-mode-switch"
				checked={mode === 'all'}
				onCheckedChange={(v) => (mode = v ? 'all' : 'active')}
				aria-label="Show all expositions"
			/>
		</div>
		<Button variant="outline" size="icon-sm" title="Refresh" aria-label="Refresh" disabled={loading} onclick={() => void load()}>
			<HugeiconsIcon icon={RefreshIcon} size={14} />
		</Button>
		<Button size="sm" disabled={!ctx.id} onclick={() => void openCreate()}>New MCP server</Button>
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
				<Table.Head>Configuration plan</Table.Head>
				<Table.Head>Backend</Table.Head>
				<Table.Head>Status</Table.Head>
				<Table.Head>Gateway group</Table.Head>
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
					<Table.Cell colspan={6} class="text-muted-foreground">No expositions for this service.</Table.Cell>
				</Table.Row>
			{:else}
				{#each rows as e (e.id)}
					{@const status = STATUS_META[e.active ? 'active' : 'inactive']}
					<Table.Row>
						<Table.Cell class="font-medium">
							<div class="flex flex-col gap-1">
								<span>
									{#if e.name}
										<a href="/expositions/{e.id}" class="text-primary hover:underline">{e.name}</a>
									{:else}
										<a
											href="/expositions/{e.id}"
											class="text-muted-foreground italic hover:underline">unnamed</a
										>
									{/if}
								</span>
								<code
									class="text-muted-foreground bg-muted w-fit rounded px-1 py-0.5 font-mono text-xs break-all"
									>{e.id}</code
								>
							</div>
						</Table.Cell>
						<Table.Cell>
							{#if e.planName || e.planId}
								<div class="flex flex-col gap-1">
									<span>{e.planName ?? '—'}</span>
									{#if e.planId}
										<code
											class="text-muted-foreground bg-muted w-fit rounded px-1 py-0.5 font-mono text-xs break-all"
											>{e.planId}</code
										>
									{/if}
								</div>
							{:else}
								<span class="text-muted-foreground">—</span>
							{/if}
						</Table.Cell>
						<Table.Cell class="max-w-xs truncate" title={e.backend}>
							<code class="truncate rounded px-1.5 py-0.5 font-mono text-xs">{e.backend}</code>
						</Table.Cell>
						<Table.Cell>
							<span
								class={cn(
									'inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
									status.classes
								)}
							>
								{status.label}
							</span>
						</Table.Cell>
						<Table.Cell>
							{#if e.gatewayGroupName || e.gatewayGroupId}
								<Badge variant="secondary" class="gap-1 text-xs">
									<HugeiconsIcon icon={TagsIcon} size={12} />
									{e.gatewayGroupName ?? e.gatewayGroupId}
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
										class="text-destructive focus:text-destructive"
										onSelect={() => void onDelete(e)}
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

<!-- Shared guided wizard, with the service pre-selected & locked. -->
<CreateExpositionDrawer
	bind:open={drawerOpen}
	serviceId={ctx.id}
	{serviceLabel}
	onCreated={() => void load()}
/>

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete exposition"
	description={deleteTarget
		? `You are about to delete the exposition "${deleteTarget.name ?? deleteTarget.id}". This action cannot be undone.`
		: undefined}
	confirmLabel="Delete"
	onConfirm={confirmDeleteExposition}
>
	<p class="text-muted-foreground text-sm">
		The associated MCP server endpoint will be taken down. Any client currently connected to this
		exposition will immediately lose access.
	</p>
</ConfirmDialog>

