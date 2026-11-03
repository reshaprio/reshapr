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
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '#lib/serviceContext.js';
	import { apiClient } from '#lib/api/client.js';
	import { parseArtifactRefList, type ArtifactRef, type ArtifactType } from '#lib/artifacts/index.js';
	import { Input } from '#lib/components/ui/input/index.js';
	import {
		Tooltip,
		TooltipContent,
		TooltipProvider,
		TooltipTrigger
	} from '#lib/components/ui/tooltip/index.js';
	import { cn } from '#lib/utils.js';
	import { toolNameForOperation } from '#lib/toolName.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		Search01Icon,
		ArrowRight01Icon,
		Wrench01Icon,
		BubbleChatIcon,
		File01Icon,
		FilterIcon
	} from '@hugeicons/core-free-icons';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	type ServiceOperation = {
		name: string;
		method: string | null;
		action: string | null;
		inputName: string | null;
		outputName: string | null;
	};

	function str(v: unknown): string | null {
		return typeof v === 'string' && v.trim() !== '' ? v : null;
	}

	const operations = $derived.by<ServiceOperation[]>(() => {
		const raw = ctx.raw as Record<string, unknown> | null;
		const ops = raw && Array.isArray(raw.operations) ? raw.operations : [];
		return ops
			.map((o): ServiceOperation | null => {
				if (!o || typeof o !== 'object') return null;
				const r = o as Record<string, unknown>;
				if (typeof r.name !== 'string') return null;
				return {
					name: r.name,
					method: str(r.method),
					action: str(r.action),
					// The API schema carries a typo ("intputName"); accept both spellings.
					inputName: str(r.inputName) ?? str(r.intputName),
					outputName: str(r.outputName)
				};
			})
			.filter((o): o is ServiceOperation => o != null);
	});

	let query = $state('');

	const filtered = $derived.by<ServiceOperation[]>(() => {
		const q = query.trim().toLowerCase();
		if (!q) return operations;
		return operations.filter(
			(o) =>
				o.name.toLowerCase().includes(q) ||
				(o.method?.toLowerCase().includes(q) ?? false) ||
				(o.action?.toLowerCase().includes(q) ?? false) ||
				(o.inputName?.toLowerCase().includes(q) ?? false) ||
				(o.outputName?.toLowerCase().includes(q) ?? false)
		);
	});


	// Color-coded pill per HTTP verb; unknown labels (e.g. gRPC actions) fall back to neutral.
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

	// --- Capabilities section ---------------------------------------------------------------------
	// Capabilities are the named elements declared by the custom artifacts attached to this service
	// (custom tool names, prompt names, resource uris, filtered tool names). They are loaded from the
	// lightweight artifact refs endpoint and grouped by type below the operations.

	let artifacts = $state<ArtifactRef[]>([]);
	let capsLoading = $state(true);

	async function loadArtifacts(id: string) {
		capsLoading = true;
		try {
			const list = await apiClient().listArtifactRefsByService(id);
			artifacts = parseArtifactRefList(list);
		} catch {
			artifacts = [];
		} finally {
			capsLoading = false;
		}
	}

	$effect(() => {
		const id = ctx.id;
		if (!id || ctx.loading) return;
		void loadArtifacts(id);
	});

	type CapabilityGroup = {
		type: ArtifactType;
		label: string;
		items: { name: string; artifactName: string }[];
	};

	// Ordered custom artifact types with their display label.
	const CUSTOM_TYPES: { type: ArtifactType; label: string }[] = [
		{ type: 'RESHAPR_CUSTOM_TOOLS', label: 'Tools' },
		{ type: 'RESHAPR_PROMPTS', label: 'Prompts' },
		{ type: 'RESHAPR_RESOURCES', label: 'Resources' },
		{ type: 'RESHAPR_TOOLS_OUTPUT_FILTERS', label: 'Output filters' }
	];

	// Distinctive icon per artifact type.
	const CAPABILITY_ICONS: Record<string, typeof Wrench01Icon> = {
		RESHAPR_CUSTOM_TOOLS: Wrench01Icon,
		RESHAPR_PROMPTS: BubbleChatIcon,
		RESHAPR_RESOURCES: File01Icon,
		RESHAPR_TOOLS_OUTPUT_FILTERS: FilterIcon
	};

	// Color-coded pill per artifact type (mirrors the operations method styling).
	const CAPABILITY_STYLES: Record<string, string> = {
		RESHAPR_CUSTOM_TOOLS: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
		RESHAPR_PROMPTS: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
		RESHAPR_RESOURCES: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
		RESHAPR_TOOLS_OUTPUT_FILTERS: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
	};

	const capabilityGroups = $derived.by<CapabilityGroup[]>(() =>
		CUSTOM_TYPES.map(({ type, label }) => {
			const items = artifacts
				.filter((a) => a.type === type && !a.mainArtifact)
				.flatMap((a) => a.capabilities.map((name) => ({ name, artifactName: a.name })));
			return { type, label, items };
		}).filter((g) => g.items.length > 0)
	);

	const totalCapabilities = $derived(
		capabilityGroups.reduce((n, g) => n + g.items.length, 0)
	);
</script>


<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
	<div class="flex items-baseline gap-2">
		<h3 class="text-base font-semibold">Operations</h3>
		{#if !ctx.loading}
			<span class="text-muted-foreground text-sm">
				{#if query.trim()}
					{filtered.length} / {operations.length}
				{:else}
					{operations.length} operation{operations.length === 1 ? '' : 's'}
				{/if}
			</span>
		{/if}
	</div>
	{#if !ctx.loading && operations.length > 0}
		<div class="relative w-full sm:w-64">
			<HugeiconsIcon
				icon={Search01Icon}
				size={16}
				class="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2"
			/>
			<Input bind:value={query} placeholder="Filter operations…" class="pl-8" />
		</div>
	{/if}
</div>

{#if ctx.loading}
	<div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
		{#each Array(6), i (i)}
			<div class="bg-muted/40 h-28 animate-pulse rounded-xl border"></div>
		{/each}
	</div>
{:else if operations.length === 0}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No operations registered on this service.</p>
	</div>
{:else if filtered.length === 0}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No operation matches “{query}”.</p>
	</div>
{:else}
	<div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
		{#each filtered as op (op.name)}
			{@const pillLabel = op.method ?? op.action}
			<div
				class="group hover:border-primary/40 relative flex flex-col gap-3 rounded-xl border p-4 transition-all hover:shadow-sm"
			>
				<div class="flex items-start justify-between gap-2">
					{#if pillLabel}
						<span
							class={cn(
								'inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs font-bold uppercase ring-1 ring-inset',
								methodStyle(op.method ?? op.action)
							)}
						>
							{pillLabel}
						</span>
					{/if}
					{#if op.method && op.action}
						<span class="text-muted-foreground text-xs">{op.action}</span>
					{/if}
				</div>

				<h4 class="leading-snug font-semibold break-all">{op.name}</h4>

				<div>
					<span
						title="Name of the MCP tool exposed for this operation"
						class={cn(
							'inline-flex max-w-full items-center gap-1.5 rounded-md px-2 py-0.5 font-mono text-xs break-all ring-1 ring-inset',
							CAPABILITY_STYLES.RESHAPR_CUSTOM_TOOLS
						)}
					>
						<HugeiconsIcon icon={Wrench01Icon} size={12} class="shrink-0" />
						{toolNameForOperation(ctx.service?.type, op.name)}
					</span>
				</div>

				{#if op.inputName || op.outputName}
					<div
						class="text-muted-foreground flex items-center gap-2 font-mono text-xs"
						title="{op.inputName ?? '—'} → {op.outputName ?? '—'}"
					>
						<span class="truncate">{op.inputName ?? '—'}</span>
						<HugeiconsIcon icon={ArrowRight01Icon} size={14} class="shrink-0 opacity-60" />
						<span class="truncate">{op.outputName ?? '—'}</span>
					</div>
				{/if}
			</div>
		{/each}
	</div>
{/if}

<!-- Capabilities: named elements declared by the custom artifacts attached to this service. -->
{#if !ctx.loading}
	{#if capsLoading}
		<div class="mt-10">
			<div class="bg-muted/60 mb-4 h-5 w-40 animate-pulse rounded"></div>
			<div class="grid gap-4 sm:grid-cols-2">
				{#each Array(2), i (i)}
					<div class="bg-muted/40 h-28 animate-pulse rounded-xl border"></div>
				{/each}
			</div>
		</div>
	{:else if capabilityGroups.length > 0}
		<section class="mt-10">
			<div class="mb-4 flex items-baseline gap-2">
				<h3 class="text-base font-semibold">Capabilities</h3>
				<span class="text-muted-foreground text-sm">
					{totalCapabilities} from attached artifacts
				</span>
			</div>
			<div class="grid gap-4 sm:grid-cols-2">
				{#each capabilityGroups as group (group.type)}
					{@const Icon = CAPABILITY_ICONS[group.type]}
					<div class="flex flex-col gap-3 rounded-xl border p-4">
						<div class="flex items-center gap-2">
							{#if Icon}
								<HugeiconsIcon icon={Icon} size={16} class="text-muted-foreground shrink-0" />
							{/if}
							<h4 class="font-semibold">{group.label}</h4>
							<span class="text-muted-foreground text-xs">{group.items.length}</span>
						</div>
						<div class="flex flex-wrap gap-1.5">
							{#each group.items as item (group.type + '::' + item.artifactName + '::' + item.name)}
								<TooltipProvider delayDuration={150}>
									<Tooltip>
										<TooltipTrigger>
											{#snippet child({ props })}
												<span
													{...props}
													class={cn(
														'inline-flex cursor-default items-center rounded-md px-2 py-0.5 font-mono text-xs ring-1 ring-inset',
														CAPABILITY_STYLES[group.type] ??
															'bg-muted text-muted-foreground ring-border'
													)}
												>
													{item.name}
												</span>
											{/snippet}
										</TooltipTrigger>
										<TooltipContent>
											<span class="text-xs">
												Defined by artifact <span class="font-medium">{item.artifactName}</span>
											</span>
										</TooltipContent>
									</Tooltip>
								</TooltipProvider>
							{/each}
						</div>
					</div>
				{/each}
			</div>
		</section>
	{/if}
{/if}

