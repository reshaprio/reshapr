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
	import { apiClient, ApiError } from '#lib/api/client.js';
	import {
		artifactTypeLabel,
		EDITABLE_KINDS,
		isEditableArtifactType,
		matchesTypeFilter,
		parseArtifactRefList,
		TYPE_FILTER_OPTIONS,
		type ArtifactRef,
		type ArtifactTypeFilter,
		type ReshaprArtifactKind
	} from '#lib/artifacts/index.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '#lib/serviceContext.js';
	import { ImportArtifactDialog } from '#lib/components/artifacts/index.js';
	import { Badge } from '#lib/components/ui/badge/index.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import * as Dialog from '#lib/components/ui/dialog/index.js';
	import { Label } from '#lib/components/ui/label/index.js';
	import * as Select from '#lib/components/ui/select/index.js';
	import * as Table from '#lib/components/ui/table/index.js';
	import {
		DropdownMenu,
		DropdownMenuContent,
		DropdownMenuItem,
		DropdownMenuTrigger
	} from '#lib/components/ui/dropdown-menu/index.js';
	import { cn } from '#lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		Delete02Icon,
		MoreVerticalIcon,
		ViewIcon,
		PencilEdit02Icon,
		Wrench01Icon,
		BubbleChatIcon,
		File01Icon,
		FilterIcon,
		RefreshIcon,
		Link01Icon
	} from '@hugeicons/core-free-icons';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	// Distinctive icon per custom artifact type (mirrors the overview Capabilities section).
	const TYPE_ICONS: Record<string, typeof Wrench01Icon> = {
		RESHAPR_CUSTOM_TOOLS: Wrench01Icon,
		RESHAPR_PROMPTS: BubbleChatIcon,
		RESHAPR_RESOURCES: File01Icon,
		RESHAPR_TOOLS_OUTPUT_FILTERS: FilterIcon
	};

	// Color-coded pill per artifact type (mirrors the overview Capabilities section styling).
	const CAPABILITY_STYLES: Record<string, string> = {
		RESHAPR_CUSTOM_TOOLS: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
		RESHAPR_PROMPTS: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
		RESHAPR_RESOURCES:
			'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
		RESHAPR_TOOLS_OUTPUT_FILTERS:
			'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
	};

	let artifacts = $state<ArtifactRef[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);

	type ImpactedPlan = { id: string; name: string; fallsBackToAll: boolean };
	type DeletionImpact = {
		artifactId: string;
		artifactName: string;
		mainArtifact: boolean;
		impactedPlans: ImpactedPlan[];
	};

	let deleteTarget = $state<ArtifactRef | null>(null);
	let deleteImpact = $state<DeletionImpact | null>(null);
	let deleteLoading = $state(false);
	let deleteBusy = $state(false);
	let deleteError = $state<string | null>(null);

	let typeFilter = $state<ArtifactTypeFilter>('all');
	let createKind = $state<ReshaprArtifactKind>('Prompts');
	let attachOpen = $state(false);


	const filterLabel = $derived(
		TYPE_FILTER_OPTIONS.find((opt) => opt.value === typeFilter)?.label ?? 'All types'
	);

	const createKindLabel = $derived(
		EDITABLE_KINDS.find((def) => def.kind === createKind)?.label ?? createKind
	);

	const filtered = $derived(
		artifacts.filter((artifact) => matchesTypeFilter(artifact, typeFilter))
	);

	function artifactHref(id: string): string {
		return `/services/${ctx.id}/artifacts/${id}`;
	}

	function createHref(): string {
		return `/services/${ctx.id}/artifacts/new?kind=${encodeURIComponent(createKind)}`;
	}

	function isUrl(value: string): boolean {
		try {
			const url = new URL(value);
			return url.protocol === 'http:' || url.protocol === 'https:';
		} catch {
			return false;
		}
	}

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		try {
			const list = await apiClient().listArtifactRefsByService(ctx.id);
			artifacts = parseArtifactRefList(list);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			artifacts = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading) void load();
	});

	async function openDelete(artifact: ArtifactRef) {
		deleteTarget = artifact;
		deleteImpact = null;
		deleteError = null;
		deleteLoading = true;
		try {
			deleteImpact = (await apiClient().getArtifactDeletionImpact(artifact.id)) as DeletionImpact;
		} catch (e) {
			deleteError = e instanceof ApiError ? e.message : String(e);
		} finally {
			deleteLoading = false;
		}
	}

	function cancelDelete() {
		if (deleteBusy) return;
		deleteTarget = null;
		deleteImpact = null;
		deleteError = null;
	}

	async function confirmDelete() {
		if (!deleteTarget) return;
		deleteBusy = true;
		deleteError = null;
		try {
			await apiClient().deleteArtifact(deleteTarget.id);
			deleteTarget = null;
			deleteImpact = null;
			await load();
		} catch (e) {
			deleteError = e instanceof ApiError ? e.message : String(e);
		} finally {
			deleteBusy = false;
		}
	}

</script>

<div class="mb-4 flex items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">Artifacts</h3>
	<div class="flex items-center gap-2">
		<Button variant="outline" size="icon-sm" title="Refresh" aria-label="Refresh" disabled={loading} onclick={() => void load()}>
			<HugeiconsIcon icon={RefreshIcon} size={14} />
		</Button>
		<Button size="sm" onclick={() => (attachOpen = true)}>
			<HugeiconsIcon icon={Link01Icon} size={14} />
			Attach artifact
		</Button>
	</div>
</div>

<ImportArtifactDialog mode="attach" bind:open={attachOpen} onDone={() => void load()} />

<p class="text-muted-foreground mb-4 text-sm">
	List, filter and manage custom artifacts here. To import a main specification, use
	<a href="/services" class="text-primary hover:underline">Import specification</a> on the Services page
	or the <strong>Quick start</strong> wizard.
</p>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="mb-4 flex flex-wrap items-end justify-between gap-4">
	<div class="space-y-2">
		<Label for="artifact-type-filter">Filter by type</Label>
		<Select.Root type="single" bind:value={typeFilter}>
			<Select.Trigger id="artifact-type-filter" class="w-[min(100%,16rem)]">
				{filterLabel}
			</Select.Trigger>
			<Select.Content>
				{#each TYPE_FILTER_OPTIONS as opt (opt.value)}
					<Select.Item value={opt.value}>{opt.label}</Select.Item>
				{/each}
			</Select.Content>
		</Select.Root>
	</div>

	<div class="flex flex-wrap items-end gap-2">
		<div class="space-y-2">
			<Label for="artifact-create-kind">New custom artifact</Label>
			<Select.Root type="single" bind:value={createKind}>
				<Select.Trigger id="artifact-create-kind" class="w-[min(100%,14rem)]">
					{createKindLabel}
				</Select.Trigger>
				<Select.Content>
					{#each EDITABLE_KINDS as def (def.kind)}
						<Select.Item value={def.kind}>{def.label}</Select.Item>
					{/each}
				</Select.Content>
			</Select.Root>
		</div>
		<Button href={createHref()} class="mb-0">Create</Button>
	</div>
</div>

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>Name</Table.Head>
				<Table.Head>Type</Table.Head>
				<Table.Head>Capabilities</Table.Head>
				<Table.Head>Role</Table.Head>
				<Table.Head>Source</Table.Head>
				<Table.Head class="w-16 text-right">Actions</Table.Head>
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#if loading}
				<Table.Row>
					<Table.Cell colspan={6} class="text-muted-foreground">Loading…</Table.Cell>
				</Table.Row>
			{:else if filtered.length === 0}
				<Table.Row>
					<Table.Cell colspan={6} class="text-muted-foreground">
						{artifacts.length === 0
							? 'No artifacts for this service.'
							: 'No artifacts match this filter.'}
					</Table.Cell>
				</Table.Row>
			{:else}
				{#each filtered as artifact (artifact.id)}
					<Table.Row>
						<Table.Cell class="font-medium">
							<div class="flex flex-col gap-1">
								<span>{artifact.name}</span>
								<code
									class="text-muted-foreground bg-muted w-fit rounded px-1 py-0.5 font-mono text-xs break-all"
									>{artifact.id}</code
								>
							</div>
						</Table.Cell>
						<Table.Cell>
							{@const TypeIcon = TYPE_ICONS[artifact.type]}
							<span class="flex items-center gap-2 text-sm">
								{#if TypeIcon}
									<HugeiconsIcon icon={TypeIcon} size={16} class="text-muted-foreground shrink-0" />
								{/if}
								{artifactTypeLabel(artifact.type)}
							</span>
						</Table.Cell>
						<Table.Cell>
							{#if artifact.capabilities.length > 0}
								<div class="flex max-w-xs flex-wrap gap-1.5">
									{#each artifact.capabilities as capability (capability)}
										<span
											class={cn(
												'inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs ring-1 ring-inset',
												CAPABILITY_STYLES[artifact.type] ??
													'bg-muted text-muted-foreground ring-border'
											)}
										>
											{capability}
										</span>
									{/each}
								</div>
							{:else}
								<span class="text-muted-foreground text-sm">—</span>
							{/if}
						</Table.Cell>
						<Table.Cell>
							{#if artifact.mainArtifact}
								<Badge variant="default">Main</Badge>
							{:else}
								<Badge variant="secondary">Attached</Badge>
							{/if}
							{#if !isEditableArtifactType(artifact.type)}
								<Badge variant="outline" class="ml-1">Read-only</Badge>
							{/if}
						</Table.Cell>
						<Table.Cell
							class="text-muted-foreground max-w-48 truncate text-sm"
							title={artifact.sourceArtifact ?? undefined}
						>
							{#if artifact.sourceArtifact}
								{#if isUrl(artifact.sourceArtifact)}
									<a
										href={artifact.sourceArtifact}
										target="_blank"
										rel="noopener noreferrer"
										class="text-primary hover:underline"
									>
										<code class="font-mono text-xs break-all">{artifact.sourceArtifact}</code>
									</a>
								{:else}
									<code class="bg-muted rounded px-1 py-0.5 font-mono text-xs break-all"
										>{artifact.sourceArtifact}</code
									>
								{/if}
							{:else}
								—
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
									<DropdownMenuItem>
										{#snippet child({ props })}
											<a href={artifactHref(artifact.id)} class="px-4" {...props}>
												<HugeiconsIcon icon={ViewIcon} size={16} />
												View
											</a>
										{/snippet}
									</DropdownMenuItem>
									{#if isEditableArtifactType(artifact.type)}
										<DropdownMenuItem>
											{#snippet child({ props })}
												<a href={artifactHref(artifact.id)} class="px-4" {...props}>
													<HugeiconsIcon icon={PencilEdit02Icon} size={16} />
													Edit
												</a>
											{/snippet}
										</DropdownMenuItem>
									{/if}
									<DropdownMenuItem
										class="text-destructive focus:text-destructive"
										onSelect={() => void openDelete(artifact)}
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

<Dialog.Root
	open={deleteTarget != null}
	onOpenChange={(open) => {
		if (!open) cancelDelete();
	}}
>
	<Dialog.Content class="sm:max-w-lg">
		<Dialog.Header>
			<Dialog.Title class="flex items-center gap-2">
				<HugeiconsIcon icon={Delete02Icon} size={20} class="text-destructive" />
				Delete artifact
			</Dialog.Title>
			<Dialog.Description>
				{#if deleteTarget}
					You are about to delete <span class="font-medium">{deleteTarget.name}</span>. This action
					cannot be undone.
				{/if}
			</Dialog.Description>
		</Dialog.Header>

		<div class="space-y-4">
			{#if deleteError}
				<ApiErrorAlert message={deleteError} />
			{/if}

			{#if deleteLoading}
				<p class="text-muted-foreground text-sm">Computing impact…</p>
			{:else if deleteImpact}
				{#if deleteImpact.mainArtifact}
					<p class="text-destructive text-sm">Warning: this is the service main artifact.</p>
				{/if}
				{#if deleteImpact.impactedPlans.length === 0}
					<p class="text-muted-foreground text-sm">
						No configuration plan references this artifact.
					</p>
				{:else}
					<div class="space-y-2">
						<p class="text-sm font-medium">
							{deleteImpact.impactedPlans.length} configuration plan(s) reference this artifact and
							will be updated:
						</p>
						<ul class="marker:text-muted-foreground list-disc space-y-1 pl-6 text-sm">
							{#each deleteImpact.impactedPlans as plan (plan.id)}
								<li>
									<span class="font-medium">{plan.name}</span>
									{#if plan.fallsBackToAll}
										<span class="text-muted-foreground block text-xs">
											Selection becomes empty → falls back to all attached artifacts.
										</span>
									{/if}
								</li>
							{/each}
						</ul>
					</div>
				{/if}
			{/if}
		</div>

		<Dialog.Footer>
			<Button variant="outline" onclick={cancelDelete} disabled={deleteBusy}>Cancel</Button>
			<Button variant="destructive" onclick={() => void confirmDelete()} disabled={deleteBusy}>
				{deleteBusy ? 'Deleting…' : 'Delete'}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

