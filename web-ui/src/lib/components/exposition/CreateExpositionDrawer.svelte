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

<!--
  Reusable guided drawer to create a new MCP server (exposition).

  The wizard walks the user through:
    1. Service selection (skipped/locked when a `serviceId` is provided by the
       hosting page, e.g. the service's own expositions tab).
    2. Configuration plan selection (scoped to the chosen service).
    3. Gateway group selection (+ optional public name).

  Shared between the global expositions list and a service's expositions tab.
-->
<script lang="ts">
	import { tick } from 'svelte';
	import { apiClient, ApiError } from '#lib/api/client.js';
	import { parseServiceRecord } from '#lib/serviceHub.js';
	import { parseArtifactRefList, type ArtifactRef } from '#lib/artifacts/index.js';
	import { avatarColor, avatarInitials } from '#lib/avatarColor.js';
	import { cn } from '#lib/utils.js';
	import { strOf, type BackendSecret } from '#lib/components/plan/planView.js';
	import PlanOperationsView from '#lib/components/plan/PlanOperationsView.svelte';
	import PlanCapabilitiesView from '#lib/components/plan/PlanCapabilitiesView.svelte';
	import PlanBackendView from '#lib/components/plan/PlanBackendView.svelte';
	import PlanClientAuthView from '#lib/components/plan/PlanClientAuthView.svelte';
	import PlanAuditView from '#lib/components/plan/PlanAuditView.svelte';
	import { Button } from '#lib/components/ui/button/index.js';
	import { Input } from '#lib/components/ui/input/index.js';
	import { Label } from '#lib/components/ui/label/index.js';
	import * as Select from '#lib/components/ui/select/index.js';
	import {
		Sheet,
		SheetContent,
		SheetHeader,
		SheetTitle,
		SheetDescription,
		SheetFooter,
		SheetClose
	} from '#lib/components/ui/sheet/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Tick02Icon } from '@hugeicons/core-free-icons';

	type ServiceOpt = { id: string; name: string; version: string; label: string };
	/** A configuration plan option keeping the raw payload for shared plan views. */
	type PlanOpt = { id: string; name: string; raw: Record<string, unknown> };
	type GroupOpt = { id: string; name: string };
	type StepKey = 'service' | 'plan' | 'gateway';

	type Props = {
		/** Two-way bound open state, controlled by the hosting page. */
		open?: boolean;
		/** When set, the service is pre-selected and locked (service context). */
		serviceId?: string | null;
		/** Optional display label for the locked service (e.g. "name:version"). */
		serviceLabel?: string | null;
		/** Called with the created exposition once creation succeeds. */
		onCreated?: (created: unknown) => void;
	};

	let {
		open = $bindable(false),
		serviceId = null,
		serviceLabel = null,
		onCreated
	}: Props = $props();

	const lockedService = $derived(!!serviceId);

	// The ordered steps depend on whether the service is already known.
	const steps = $derived<StepKey[]>(
		lockedService ? ['plan', 'gateway'] : ['service', 'plan', 'gateway']
	);
	const STEP_LABELS: Record<StepKey, string> = {
		service: 'Service',
		plan: 'Configuration plan',
		gateway: 'Gateway group'
	};

	let stepIndex = $state(0);
	const currentStep = $derived(steps[stepIndex]);
	const isLastStep = $derived(stepIndex === steps.length - 1);

	// ── Selections ────────────────────────────────────────────
	let selectedServiceId = $state('');
	let selectedPlanId = $state('');
	let selectedGroupId = $state('');
	let expoName = $state('');

	// ── Loaded data ───────────────────────────────────────────
	let services = $state<ServiceOpt[]>([]);
	let plans = $state<PlanOpt[]>([]);
	let groups = $state<GroupOpt[]>([]);
	// Reference data used by the shared plan views (capabilities & backend auth).
	let artifacts = $state<ArtifactRef[]>([]);
	let backendSecret = $state<BackendSecret | null>(null);
	let loadingServices = $state(false);
	let loadingPlans = $state(false);
	let loadingGroups = $state(false);

	let submitting = $state(false);
	let formError = $state('');

	function asRecord(raw: unknown): Record<string, unknown> | null {
		return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : null;
	}

	/** Parse a raw configuration plan into an option keeping its raw payload. */
	function parsePlan(raw: unknown, sid: string): PlanOpt | null {
		const o = asRecord(raw);
		if (!o || typeof o.id !== 'string' || o.serviceId !== sid) return null;
		return {
			id: o.id,
			name: typeof o.name === 'string' && o.name.trim() ? o.name.trim() : o.id,
			raw: o
		};
	}

	// ── Derived selection labels (for the Select triggers & summary) ──
	const selectedService = $derived(services.find((s) => s.id === selectedServiceId) ?? null);
	const selectedPlan = $derived(plans.find((p) => p.id === selectedPlanId) ?? null);
	const selectedGroup = $derived(groups.find((g) => g.id === selectedGroupId) ?? null);

	const serviceTriggerLabel = $derived(
		lockedService
			? (serviceLabel ?? selectedService?.label ?? 'Selected service')
			: (selectedService?.label ?? 'Select a service')
	);
	const planTriggerLabel = $derived(selectedPlan?.name ?? 'Select a configuration plan');
	const groupTriggerLabel = $derived(selectedGroup?.name ?? 'Select a gateway group');

	// ── Default (slugified) exposition name ───────────────────
	/** Turn arbitrary text into a URL/DNS-friendly slug. */
	function slugify(input: string): string {
		return input
			.normalize('NFKD')
			.replace(/[\u0300-\u036f]/g, '')
			.toLowerCase()
			.replace(/[^a-z0-9]+/g, '-')
			.replace(/-{2,}/g, '-')
			.replace(/^-+|-+$/g, '');
	}

	// Suggested name derived from the service (name + version) and the plan name.
	const defaultName = $derived.by(() => {
		const svcPart = selectedService
			? `${selectedService.name} ${selectedService.version}`
			: (serviceLabel ?? '');
		const planPart = selectedPlan?.name ?? '';
		const combined = `${svcPart} ${planPart}`.trim();
		return combined ? slugify(combined) : '';
	});

	// Track whether the user manually edited the name so we stop auto-filling it.
	let nameEdited = $state(false);
	// Keep the name in sync with the suggested default until the user edits it.
	$effect(() => {
		if (!nameEdited) expoName = defaultName;
	});

	// Whether the current step has a valid selection so we can advance / submit.
	const canProceed = $derived(
		currentStep === 'service'
			? !!selectedServiceId
			: currentStep === 'plan'
				? !!selectedPlanId
				: !!selectedGroupId && expoName.trim() !== ''
	);

	// ── Data loading ──────────────────────────────────────────
	async function loadServices() {
		loadingServices = true;
		try {
			const data = await apiClient().listServices();
			const list = Array.isArray(data) ? data : [];
			services = list
				.map((raw) => {
					const rec = parseServiceRecord(raw);
					if (!rec) return null;
					const version = rec.version && rec.version !== '—' ? rec.version : '';
					return {
						id: rec.id,
						name: rec.name,
						version,
						label: version ? `${rec.name}:${version}` : rec.name
					} satisfies ServiceOpt;
				})
				.filter((s): s is ServiceOpt => s != null);
		} catch (e) {
			formError = e instanceof ApiError ? e.message : String(e);
		} finally {
			loadingServices = false;
		}
	}

	async function loadPlans(sid: string) {
		if (!sid) {
			plans = [];
			return;
		}
		loadingPlans = true;
		try {
			const data = await apiClient().listConfigurationPlans();
			const list = Array.isArray(data) ? data : [];
			plans = list
				.map((raw) => parsePlan(raw, sid))
				.filter((p): p is PlanOpt => p != null);
		} catch (e) {
			formError = e instanceof ApiError ? e.message : String(e);
		} finally {
			loadingPlans = false;
		}
	}

	async function loadGroups() {
		loadingGroups = true;
		try {
			const data = await apiClient().listGatewayGroups();
			const list = Array.isArray(data) ? data : [];
			groups = list
				.map((raw) => {
					const o = asRecord(raw);
					if (!o || typeof o.id !== 'string') return null;
					return {
						id: o.id,
						name: typeof o.name === 'string' && o.name.trim() ? o.name.trim() : o.id
					} satisfies GroupOpt;
				})
				.filter((g): g is GroupOpt => g != null);
		} catch (e) {
			formError = e instanceof ApiError ? e.message : String(e);
		} finally {
			loadingGroups = false;
		}
	}

	async function loadArtifacts(sid: string) {
		if (!sid) {
			artifacts = [];
			return;
		}
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
				.map(asRecord)
				.find((s) => s && strOf(s.id) === secretId);
			backendSecret = match
				? { name: strOf(match.name) ?? secretId, type: strOf(match.type) ?? '' }
				: { name: secretId, type: '' };
		} catch {
			backendSecret = { name: secretId, type: '' };
		}
	}

	function resetWizard() {
		stepIndex = 0;
		selectedServiceId = lockedService ? (serviceId ?? '') : '';
		selectedPlanId = '';
		selectedGroupId = '';
		expoName = '';
		nameEdited = false;
		formError = '';
	}

	// Load fresh data every time the drawer opens and reset the wizard state.
	$effect(() => {
		if (!open) return;
		resetWizard();
		void loadGroups();
		if (lockedService) {
			void loadPlans(serviceId ?? '');
			void loadArtifacts(serviceId ?? '');
		} else {
			void loadServices();
		}
	});

	// When the selected service changes (unlocked flow), reload its plans and
	// artifacts, and drop any stale plan selection.
	$effect(() => {
		if (lockedService) return;
		const sid = selectedServiceId;
		selectedPlanId = '';
		void loadPlans(sid);
		void loadArtifacts(sid);
	});

	// Resolve the backend secret reference for the selected plan (for its display).
	$effect(() => {
		const plan = selectedPlan;
		backendSecret = null;
		const secretId = plan ? strOf(plan.raw.backendSecretId) : null;
		if (secretId) void loadBackendSecret(secretId);
	});

	// Work around a bits-ui body-scroll-lock issue seen across drawers: force
	// unfreeze the body after the close restore window whenever the drawer closes.
	$effect(() => {
		if (open) return;
		const unfreeze = () => {
			if (document.body.style.pointerEvents === 'none') {
				document.body.style.removeProperty('pointer-events');
				document.body.style.removeProperty('overflow');
			}
		};
		const timers = [50, 200].map((d) => setTimeout(unfreeze, d));
		return () => timers.forEach(clearTimeout);
	});

	function next() {
		formError = '';
		if (!canProceed) return;
		if (!isLastStep) stepIndex += 1;
	}

	function back() {
		formError = '';
		if (stepIndex > 0) stepIndex -= 1;
	}

	async function onSubmit(ev: SubmitEvent) {
		ev.preventDefault();
		formError = '';
		// Guard against submitting from a non-final step (e.g. Enter key).
		if (!isLastStep) {
			next();
			return;
		}
		if (!selectedPlanId || !selectedGroupId) {
			formError = 'Please complete all steps before creating the MCP server.';
			return;
		}
		const name = expoName.trim();
		if (!name) {
			formError = 'A name is required for the MCP server.';
			return;
		}
		submitting = true;
		try {
			const created = await apiClient().createExposition({
				configurationPlanId: selectedPlanId,
				gatewayGroupId: selectedGroupId,
				name
			});
			open = false;
			await tick();
			onCreated?.(created);
		} catch (e) {
			formError = e instanceof ApiError ? e.message : String(e);
		} finally {
			submitting = false;
		}
	}
</script>

<Sheet bind:open>
	<SheetContent side="right" class="flex flex-col sm:max-w-lg">
		<SheetHeader>
			<SheetTitle>Create MCP server</SheetTitle>
			<SheetDescription>
				Expose a service as an MCP server in a few guided steps.
			</SheetDescription>
		</SheetHeader>

		<!-- Step indicator -->
		<ol class="flex items-center gap-2 px-4 text-xs">
			{#each steps as step, i (step)}
				{@const state = i < stepIndex ? 'done' : i === stepIndex ? 'current' : 'todo'}
				<li class="flex min-w-0 items-center gap-2">
					<span
						class={cn(
							'flex size-6 shrink-0 items-center justify-center rounded-full border text-[11px] font-semibold',
							state === 'done' && 'border-primary bg-primary text-primary-foreground',
							state === 'current' && 'border-primary text-primary',
							state === 'todo' && 'border-border text-muted-foreground'
						)}
					>
						{#if state === 'done'}
							<HugeiconsIcon icon={Tick02Icon} size={14} />
						{:else}
							{i + 1}
						{/if}
					</span>
					<span
						class={cn(
							'truncate',
							state === 'todo' ? 'text-muted-foreground' : 'text-foreground font-medium'
						)}
					>
						{STEP_LABELS[step]}
					</span>
					{#if i < steps.length - 1}
						<span class="bg-border mx-1 h-px w-4 shrink-0" aria-hidden="true"></span>
					{/if}
				</li>
			{/each}
		</ol>

		<form onsubmit={onSubmit} class="flex flex-1 flex-col gap-4 overflow-y-auto px-4">
			{#if currentStep === 'service'}
				<!-- ── Step: Service ─────────────────────────────── -->
				<div class="space-y-2">
					<Label for="expo-service">Service <span class="text-destructive">*</span></Label>
					<Select.Root type="single" bind:value={selectedServiceId}>
						<Select.Trigger id="expo-service" class="w-full">{serviceTriggerLabel}</Select.Trigger>
						<Select.Content>
							{#if loadingServices}
								<div class="text-muted-foreground px-2 py-1.5 text-sm">Loading…</div>
							{:else if services.length === 0}
								<div class="text-muted-foreground px-2 py-1.5 text-sm">No services available</div>
							{:else}
								{#each services as s (s.id)}
									<Select.Item value={s.id}>{s.label}</Select.Item>
								{/each}
							{/if}
						</Select.Content>
					</Select.Root>
					<p class="text-muted-foreground text-xs">
						Choose the service you want to expose as an MCP server.
					</p>
				</div>
			{:else if currentStep === 'plan'}
				<!-- ── Step: Configuration plan ──────────────────── -->
				<div class="space-y-2">
					<Label for="expo-plan">
						Configuration plan <span class="text-destructive">*</span>
					</Label>
					<Select.Root type="single" bind:value={selectedPlanId}>
						<Select.Trigger id="expo-plan" class="w-full">{planTriggerLabel}</Select.Trigger>
						<Select.Content>
							{#if loadingPlans}
								<div class="text-muted-foreground px-2 py-1.5 text-sm">Loading…</div>
							{:else if plans.length === 0}
								<div class="text-muted-foreground px-2 py-1.5 text-sm">
									No configuration plan for this service
								</div>
							{:else}
								{#each plans as p (p.id)}
									<Select.Item value={p.id}>{p.name}</Select.Item>
								{/each}
							{/if}
						</Select.Content>
					</Select.Root>
					{#if selectedPlan}
						<div class="bg-muted/30 space-y-4 rounded-lg border p-3 text-sm">
							<PlanBackendView plan={selectedPlan.raw} {backendSecret} />
							<div class="border-t pt-4">
								<PlanOperationsView plan={selectedPlan.raw} />
							</div>
							<div class="border-t pt-4">
								<PlanCapabilitiesView plan={selectedPlan.raw} {artifacts} />
							</div>
							<div class="border-t pt-4">
								<PlanClientAuthView plan={selectedPlan.raw} />
							</div>
							<div class="border-t pt-4">
								<PlanAuditView plan={selectedPlan.raw} />
							</div>
						</div>
					{:else}
						<p class="text-muted-foreground text-xs">
							Pick the configuration plan describing the backend and included operations.
						</p>
					{/if}
				</div>
			{:else}
				<!-- ── Step: Gateway group ───────────────────────── -->
				<div class="space-y-2">
					<Label for="expo-group">Gateway group <span class="text-destructive">*</span></Label>
					<Select.Root type="single" bind:value={selectedGroupId}>
						<Select.Trigger id="expo-group" class="w-full">{groupTriggerLabel}</Select.Trigger>
						<Select.Content>
							{#if loadingGroups}
								<div class="text-muted-foreground px-2 py-1.5 text-sm">Loading…</div>
							{:else if groups.length === 0}
								<div class="text-muted-foreground px-2 py-1.5 text-sm">No gateway group available</div>
							{:else}
								{#each groups as g (g.id)}
									<Select.Item value={g.id}>{g.name}</Select.Item>
								{/each}
							{/if}
						</Select.Content>
					</Select.Root>
					<p class="text-muted-foreground text-xs">
						The gateway group determines on which gateways the MCP server is reachable.
					</p>
				</div>

				<!-- ── Step: MCP server name (own, clearly separated block) ── -->
				<div class="space-y-2 rounded-lg border p-4">
					<div class="flex items-center justify-between gap-2">
						<Label for="expo-name" class="text-sm font-medium">
							MCP server name <span class="text-destructive">*</span>
						</Label>
						{#if nameEdited && defaultName && expoName.trim() !== defaultName}
							<Button
								type="button"
								variant="ghost"
								size="sm"
								class="h-6 px-2 text-xs"
								onclick={() => {
									nameEdited = false;
									expoName = defaultName;
								}}
							>
								Reset to default
							</Button>
						{/if}
					</div>
					<Input
						id="expo-name"
						class="w-full"
						bind:value={expoName}
						oninput={() => (nameEdited = true)}
						placeholder="e.g. github-read-only"
						autocomplete="off"
						spellcheck={false}
						required
						aria-invalid={expoName.trim() === ''}
					/>
					<p class="text-muted-foreground text-xs">
						The MCP server will be reachable at
						<code class="text-xs">/mcp/&lbrace;org&rbrace;/&lbrace;name&rbrace;</code>. The name must be unique
						within your organization.
					</p>
				</div>

				<!-- Recap of previous selections -->
				<div class="bg-muted/40 space-y-1 rounded-lg border p-3 text-xs">
					<div class="flex items-center gap-2">
						<span
							class="flex size-6 shrink-0 items-center justify-center rounded text-[10px] font-semibold text-white"
							style="background-color: {avatarColor(
								selectedService?.name || serviceLabel || 'service'
							)};"
							aria-hidden="true"
						>
							{avatarInitials(selectedService?.name || serviceLabel || 'S')}
						</span>
						<span class="text-muted-foreground">Service:</span>
						<span class="truncate font-medium">{serviceTriggerLabel}</span>
					</div>
					<div class="flex items-center gap-2">
						<span class="text-muted-foreground">Plan:</span>
						<span class="truncate font-medium">{planTriggerLabel}</span>
					</div>
				</div>
			{/if}

			{#if formError}
				<div class="bg-destructive/10 text-destructive rounded-md px-4 py-3 text-sm">
					{formError}
				</div>
			{/if}

			<SheetFooter class="mt-auto flex-row justify-between gap-2 pt-4">
				{#if stepIndex > 0}
					<Button type="button" variant="outline" onclick={back} disabled={submitting}>Back</Button>
				{:else}
					<SheetClose>
						{#snippet child({ props })}
							<Button variant="outline" type="button" {...props}>Cancel</Button>
						{/snippet}
					</SheetClose>
				{/if}

				{#if isLastStep}
					<Button type="submit" disabled={submitting || !canProceed}>
						{#if submitting}
							<div
								class="border-primary-foreground h-4 w-4 animate-spin rounded-full border-2 border-t-transparent"
							></div>
							Creating…
						{:else}
							Create MCP server
						{/if}
					</Button>
				{:else}
					<Button type="button" onclick={next} disabled={!canProceed}>Next</Button>
				{/if}
			</SheetFooter>
		</form>
	</SheetContent>
</Sheet>

