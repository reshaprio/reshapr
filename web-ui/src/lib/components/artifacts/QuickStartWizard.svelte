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
	import ImportArtifactForm from './ImportArtifactForm.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Dialog from '$lib/components/ui/dialog/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Switch } from '$lib/components/ui/switch/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { parseServiceRecord } from '$lib/serviceHub.js';
	import { planBelongsToService } from '$lib/serviceHub.js';
	import { parseOperationsList } from '$lib/operationsList.js';
	import { cn } from '$lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Tick02Icon, ArrowRight01Icon, Copy01Icon } from '@hugeicons/core-free-icons';

	/**
	 * Quick start wizard: guides a first-time user through importing a
	 * specification, optionally attaching artifacts, creating a configuration plan
	 * and (optionally) exposing it — all from a single large fixed-size dialog.
	 *
	 * It reuses {@link ImportArtifactForm} for the import and attach steps and
	 * mirrors the plan/authentication options from `PlanEditor` for the guided
	 * plan creation.
	 */
	let { open = $bindable(false) }: { open?: boolean } = $props();

	// The default gateway group used by the "Expose now" shortcut.
	// Kept in sync with GatewayGroup.DEFAULT_GATEWAY_GROUP_ID (control-plane) — provisioned by Flyway V1.0.0.
	const DEFAULT_GATEWAY_GROUP_ID = '1';

	// Plan name aligned with the CLI convention.
	const DEFAULT_PLAN_NAME = 'default';

	/** Build a URL/name friendly slug from arbitrary text parts. */
	function slugify(...parts: (string | null | undefined)[]): string {
		return parts
			.filter((p): p is string => !!p && p.trim() !== '' && p.trim() !== '—')
			.join('-')
			.toLowerCase()
			.normalize('NFKD')
			.replace(/[\u0300-\u036f]/g, '')
			.replace(/[^a-z0-9]+/g, '-')
			.replace(/^-+|-+$/g, '');
	}

	type View = 'import' | 'attach' | 'plan' | 'finish';

	const STEPS: { view: View; label: string }[] = [
		{ view: 'import', label: 'Import' },
		{ view: 'attach', label: 'Artifacts' },
		{ view: 'plan', label: 'Plan' },
		{ view: 'finish', label: 'Expose' }
	];

	let view = $state<View>('import');
	let error = $state<string | null>(null);
	let busy = $state(false);

	// Imported service.
	let serviceId = $state('');
	let serviceName = $state('');
	let serviceVersion = $state('');

	// Import step.
	let importForm = $state<ReturnType<typeof ImportArtifactForm> | null>(null);
	let importSubmitting = $state(false);

	// Attach step.
	let hasArtifacts = $state(false);
	let attachForm = $state<ReturnType<typeof ImportArtifactForm> | null>(null);
	let attachSubmitting = $state(false);
	let attached = $state<{ name: string; type: string }[]>([]);

	// Plan step.
	let fineGrained = $state(false);
	let backendEndpoint = $state('');
	let secure = $state(false);
	let mcpAuthMode = $state<'apikey' | 'oauth'>('apikey');
	let oauthAuthServersText = $state('');
	let oauthJwksUri = $state('');
	let oauthScopesText = $state('');
	let oauthDisableAudienceVal = $state(false);
	let oauthStaticAudiencesText = $state('');

	// Finish step.
	let planId = $state('');
	let planExisted = $state(false);
	let createdKey = $state<string | null>(null);
	let copied = $state(false);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;

	/** Copy the freshly generated API key to the clipboard (once-shown value). */
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

	const currentStepIndex = $derived(STEPS.findIndex((s) => s.view === view));

	function resetAll() {
		view = 'import';
		error = null;
		busy = false;
		serviceId = '';
		serviceName = '';
		serviceVersion = '';
		importSubmitting = false;
		hasArtifacts = false;
		attachSubmitting = false;
		attached = [];
		fineGrained = false;
		backendEndpoint = '';
		secure = false;
		mcpAuthMode = 'apikey';
		oauthAuthServersText = '';
		oauthJwksUri = '';
		oauthScopesText = '';
		oauthDisableAudienceVal = false;
		oauthStaticAudiencesText = '';
		planId = '';
		planExisted = false;
		createdKey = null;
		copied = false;
	}

	// Reset state each time the wizard is (re)opened.
	let prevOpen = false;
	$effect(() => {
		if (open && !prevOpen) resetAll();
		prevOpen = open;
	});

	function handleOpenChange(next: boolean) {
		if (busy) return;
		open = next;
	}

	function close() {
		open = false;
	}

	// ── Step 1: import specification ─────────────────────────────────────────
	async function doImport() {
		error = null;
		const res = await importForm?.submit();
		if (res == null) return;
		const rec = parseServiceRecord(res);
		if (!rec) {
			error = 'Unexpected import response: could not read the created service.';
			return;
		}
		serviceId = rec.id;
		serviceName = rec.name !== '—' ? rec.name : '';
		serviceVersion = rec.version !== '—' ? rec.version : '';
		hasArtifacts = false;
		view = 'attach';
	}

	// ── Step 2: attach artifacts ─────────────────────────────────────────────
	async function doAttach() {
		error = null;
		const res = await attachForm?.submit();
		if (res == null) return;
		const o = (res ?? {}) as Record<string, unknown>;
		attached = [
			...attached,
			{
				name: typeof o.name === 'string' ? o.name : 'artifact',
				type: typeof o.type === 'string' ? o.type : ''
			}
		];
	}

	// ── Step 3: create (or update) the "default" configuration plan ──────────
	/** Apply the chosen MCP endpoint authentication onto a plan body. */
	function applyAuth(body: Record<string, unknown>, isUpdate: boolean) {
		if (secure && mcpAuthMode === 'oauth') {
			body.oauth2Configuration = {
				authorizationServers: parseOperationsList(oauthAuthServersText),
				jwksUri: oauthJwksUri.trim() || null,
				scopes: parseOperationsList(oauthScopesText),
				disableAudienceValidation: oauthDisableAudienceVal,
				staticAudiences: parseOperationsList(oauthStaticAudiencesText)
			};
			delete body.apiKey;
			return;
		}
		delete body.oauth2Configuration;
		if (secure && mcpAuthMode === 'apikey') {
			// On create, ask the backend to generate a key. On update, keep the
			// existing key (rotate it later from the plan editor).
			if (!isUpdate) body.apiKey = 'generate-me';
		} else {
			delete body.apiKey;
		}
	}

	async function createPlan() {
		error = null;
		if (!backendEndpoint.trim()) {
			error = 'Backend endpoint URL is required.';
			return;
		}
		busy = true;
		try {
			const client = apiClient();

			// Reuse the existing "default" plan for this service if there is one.
			const plans = (await client.listConfigurationPlans()) as Record<string, unknown>[];
			const existing = (Array.isArray(plans) ? plans : []).find(
				(p) => planBelongsToService(p, serviceId) && p.name === DEFAULT_PLAN_NAME
			);

			let out: { id: string; apiKey?: string };
			if (existing && typeof existing.id === 'string') {
				// Update the existing default plan, preserving unknown fields.
				const base = (await client.getConfigurationPlan(existing.id)) as Record<string, unknown>;
				const body: Record<string, unknown> = {
					...base,
					name: DEFAULT_PLAN_NAME,
					description: 'Managed with the Quick start wizard.',
					serviceId,
					backendEndpoint: backendEndpoint.trim()
				};
				applyAuth(body, true);
				out = (await client.updateConfigurationPlan(existing.id, body)) as {
					id: string;
					apiKey?: string;
				};
				planId = existing.id;
				planExisted = true;
				createdKey = null;
			} else {
				const body: Record<string, unknown> = {
					name: DEFAULT_PLAN_NAME,
					description: 'Created with the Quick start wizard.',
					serviceId,
					backendEndpoint: backendEndpoint.trim()
				};
				applyAuth(body, false);
				out = (await client.createConfigurationPlan(body)) as { id: string; apiKey?: string };
				planId = out.id;
				planExisted = false;
				createdKey = out.apiKey ?? null;
			}
			view = 'finish';
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			busy = false;
		}
	}

	// Redirect to the advanced plan editor for fine-grained operations/capabilities.
	async function openAdvancedEditor() {
		const id = serviceId;
		close();
		await goto(`/services/${id}/plans/new`);
	}

	// ── Step 4: expose ───────────────────────────────────────────────────────
	async function exposeLater() {
		const id = serviceId;
		close();
		await goto(`/services/${id}/plans`);
	}

	async function exposeNow() {
		error = null;
		busy = true;
		try {
			// Default exposition name: slug of service name, version and plan name.
			const expositionName = slugify(serviceName, serviceVersion, DEFAULT_PLAN_NAME);
			const expo = (await apiClient().createExposition({
				configurationPlanId: planId,
				gatewayGroupId: DEFAULT_GATEWAY_GROUP_ID,
				name: expositionName || undefined
			})) as { id?: string };
			const id = serviceId;
			close();
			// Redirect to the new MCP Server (exposition) detail page when available,
			// otherwise fall back to the service's expositions list.
			await goto(expo?.id ? `/expositions/${expo.id}` : `/services/${id}/expositions`);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			busy = false;
		}
	}
</script>

<Dialog.Root {open} onOpenChange={handleOpenChange}>
	<Dialog.Content
		class="flex h-160 w-full max-w-[min(92vw,900px)] flex-col gap-0 p-0 sm:max-w-3xl"
		showCloseButton={!busy}
	>
		<!-- Header + stepper -->
		<div class="space-y-4 border-b p-6">
			<div>
				<Dialog.Title class="text-lg">Quick start</Dialog.Title>
				<Dialog.Description>
					Import an API, optionally attach artifacts, configure a plan and expose it as an MCP Server — in a few
					steps.
				</Dialog.Description>
			</div>
			<ol class="flex items-center gap-2">
				{#each STEPS as step, i (step.view)}
					<li class="flex items-center gap-2">
						<span
							class={cn(
								'inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold',
								i < currentStepIndex
									? 'bg-primary text-primary-foreground'
									: i === currentStepIndex
										? 'bg-primary/15 text-primary ring-primary ring-2'
										: 'bg-muted text-muted-foreground'
							)}
						>
							{#if i < currentStepIndex}
								<HugeiconsIcon icon={Tick02Icon} size={14} />
							{:else}
								{i + 1}
							{/if}
						</span>
						<span
							class={cn(
								'text-sm',
								i === currentStepIndex ? 'text-foreground font-medium' : 'text-muted-foreground'
							)}
						>
							{step.label}
						</span>
						{#if i < STEPS.length - 1}
							<HugeiconsIcon
								icon={ArrowRight01Icon}
								size={14}
								class="text-muted-foreground/50 mx-1"
							/>
						{/if}
					</li>
				{/each}
			</ol>
		</div>

		<!-- Body -->
		<div class="flex-1 overflow-y-auto p-6">
			{#if error}
				<div class="mb-4">
					<ApiErrorAlert message={error} />
				</div>
			{/if}

			{#if view === 'import'}
				<div class="space-y-4">
					<p class="text-muted-foreground text-sm">
						Import an OpenAPI, GraphQL or Protobuf specification. A new service and its main artifact
						will be created.
					</p>
					<ImportArtifactForm bind:this={importForm} mode="import" bind:submitting={importSubmitting} />
				</div>
			{:else if view === 'attach'}
				<div class="space-y-4">
					<div
						class="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-2 text-sm"
					>
						<HugeiconsIcon icon={Tick02Icon} size={16} class="text-primary" />
						<span>
							Service <span class="font-medium">{serviceName || serviceId}</span> imported.
						</span>
					</div>

					<div class="flex items-center justify-between gap-4 rounded-lg border p-4">
						<div class="space-y-0.5">
							<Label for="qs-has-artifacts" class="text-sm font-medium">
								Attach complementary artifacts
							</Label>
							<p class="text-muted-foreground text-sm">
								Custom tools, prompts, resources or output filters can enrich this service.
							</p>
						</div>
						<Switch
							id="qs-has-artifacts"
							checked={hasArtifacts}
							onCheckedChange={(v) => (hasArtifacts = v)}
						/>
					</div>

					{#if attached.length > 0}
						<div class="space-y-2">
							<p class="text-sm font-medium">Attached ({attached.length})</p>
							<ul class="space-y-1">
								{#each attached as art, i (i)}
									<li
										class="flex items-center gap-2 rounded-md border px-2 py-1.5 text-sm"
									>
										<HugeiconsIcon icon={Tick02Icon} size={14} class="text-primary shrink-0" />
										<span class="font-medium">{art.name}</span>
										{#if art.type}
											<code class="text-muted-foreground text-xs">{art.type}</code>
										{/if}
									</li>
								{/each}
							</ul>
						</div>
					{/if}

					{#if hasArtifacts}
						<div class="space-y-3 rounded-lg border p-4">
							<p class="text-sm font-medium">Attach an artifact</p>
							<ImportArtifactForm bind:this={attachForm} mode="attach" bind:submitting={attachSubmitting} />
							<Button variant="secondary" onclick={() => void doAttach()} disabled={attachSubmitting}>
								{attachSubmitting ? 'Attaching…' : 'Attach this artifact'}
							</Button>
						</div>
					{/if}
				</div>
			{:else if view === 'plan'}
				<div class="space-y-4">
					<div class="flex items-center justify-between gap-4 rounded-lg border p-4">
						<div class="space-y-0.5">
							<Label for="qs-fine-grained" class="text-sm font-medium">
								Fine-grained operations &amp; capabilities
							</Label>
							<p class="text-muted-foreground text-sm">
								Pick exactly which operations, artifacts and capabilities to expose in the advanced
								editor.
							</p>
						</div>
						<Switch
							id="qs-fine-grained"
							checked={fineGrained}
							onCheckedChange={(v) => (fineGrained = v)}
						/>
					</div>

					{#if fineGrained}
						<div class="bg-muted/50 space-y-3 rounded-lg border p-4">
							<p class="text-muted-foreground text-sm">
								The advanced plan editor lets you select exposed operations, artifacts and
								capabilities. You'll leave the wizard to continue there.
							</p>
							<Button onclick={() => void openAdvancedEditor()}>Open advanced plan editor</Button>
						</div>
					{:else}
						<div class="space-y-4">
							<div class="space-y-2">
								<Label for="qs-backend-endpoint">
									Backend endpoint URL <span class="text-destructive">*</span>
								</Label>
								<Input
									id="qs-backend-endpoint"
									bind:value={backendEndpoint}
									class="w-full"
									placeholder="https://api.backend.acme.com"
								/>
							</div>

							<div class="flex items-center justify-between gap-4 rounded-lg border p-4">
								<div class="space-y-0.5">
									<Label for="qs-secure" class="text-sm font-medium">
										Secure access to the MCP endpoint
									</Label>
									<p class="text-muted-foreground text-sm">
										Require authentication (API key or OAuth) from MCP clients.
									</p>
								</div>
								<Switch id="qs-secure" checked={secure} onCheckedChange={(v) => (secure = v)} />
							</div>

							{#if secure}
								<div class="space-y-3 rounded-lg border p-4">
									<div class="flex flex-wrap gap-2">
										<Button
											variant={mcpAuthMode === 'apikey' ? 'default' : 'outline'}
											size="sm"
											onclick={() => (mcpAuthMode = 'apikey')}
										>
											API Key
										</Button>
										<Button
											variant={mcpAuthMode === 'oauth' ? 'default' : 'outline'}
											size="sm"
											onclick={() => (mcpAuthMode = 'oauth')}
										>
											OAuth
										</Button>
									</div>

									{#if mcpAuthMode === 'apikey'}
										<p class="text-muted-foreground text-sm">
											An API key will be generated when the plan is created and shown once — copy it
											immediately.
										</p>
									{:else}
										<div class="space-y-2">
											<Label for="qs-oauth-servers">Authorization servers</Label>
											<Textarea
												id="qs-oauth-servers"
												bind:value={oauthAuthServersText}
												rows={2}
												placeholder={'https://auth.example.com/realms/main'}
											/>
											<p class="text-muted-foreground text-xs">One issuer URL per line.</p>
										</div>
										<div class="space-y-2">
											<Label for="qs-oauth-jwks">JWKS URI</Label>
											<Input
												id="qs-oauth-jwks"
												bind:value={oauthJwksUri}
												class="w-full"
												placeholder="https://auth.example.com/realms/main/protocol/openid-connect/certs"
											/>
										</div>
										<div class="space-y-2">
											<Label for="qs-oauth-scopes">Scopes</Label>
											<Textarea
												id="qs-oauth-scopes"
												bind:value={oauthScopesText}
												rows={2}
												placeholder={'openid\nmcp:invoke'}
											/>
											<p class="text-muted-foreground text-xs">One scope per line.</p>
										</div>
										<div class="flex items-center justify-between gap-4 rounded-lg border p-4">
											<div class="space-y-0.5">
												<Label for="qs-oauth-disable-aud" class="text-sm font-medium">
													Disable audience validation
												</Label>
												<p class="text-muted-foreground text-sm">
													If enabled, bypasses validating the aud claim in OAuth tokens.
												</p>
											</div>
											<Switch id="qs-oauth-disable-aud" checked={oauthDisableAudienceVal} onCheckedChange={(v) => (oauthDisableAudienceVal = v)} />
										</div>
										{#if !oauthDisableAudienceVal}
											<div class="space-y-2">
												<Label for="qs-oauth-static-aud">Static audiences</Label>
												<Textarea
													id="qs-oauth-static-aud"
													bind:value={oauthStaticAudiencesText}
													rows={2}
													placeholder={'https://api.example.com'}
												/>
												<p class="text-muted-foreground text-xs">One audience per line.</p>
											</div>
										{/if}
									{/if}
								</div>
							{/if}
						</div>
					{/if}
				</div>
			{:else if view === 'finish'}
				<div class="space-y-4">
					<div class="flex items-center gap-2 rounded-lg border border-green-600/30 bg-green-500/10 px-3 py-2 text-sm">
						<HugeiconsIcon icon={Tick02Icon} size={16} class="text-green-600" />
						<span>
							Configuration plan <code class="text-xs">default</code>
							{planExisted ? 'updated' : 'created'}.
						</span>
					</div>

					{#if createdKey}
						<div class="rounded-lg border border-amber-500/30 bg-amber-500/10 p-4">
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
								<Button
									variant="outline"
									size="sm"
									class="shrink-0"
									onclick={() => void copyCreatedKey()}
								>
									{#if copied}
										<HugeiconsIcon icon={Tick02Icon} size={16} />
										Copied
									{:else}
										<HugeiconsIcon icon={Copy01Icon} size={16} />
										Copy
									{/if}
								</Button>
							</div>
						</div>
					{/if}

					<div class="space-y-2">
						<p class="text-sm font-medium">How do you want to proceed?</p>
						<p class="text-muted-foreground text-sm">
							Expose now on the default gateway group, or exit and choose a gateway group later.
						</p>
					</div>
				</div>
			{/if}
		</div>

		<!-- Footer -->
		<div class="flex items-center justify-between gap-2 border-t p-4">
			{#if view === 'import'}
				<Button variant="ghost" onclick={close} disabled={busy}>Cancel</Button>
				<Button onclick={() => void doImport()} disabled={importSubmitting}>
					{importSubmitting ? 'Importing…' : 'Import & continue'}
				</Button>
			{:else if view === 'attach'}
				<Button variant="ghost" onclick={close} disabled={busy}>Cancel</Button>
				<Button onclick={() => (view = 'plan')} disabled={attachSubmitting}>Continue</Button>
			{:else if view === 'plan'}
				<Button variant="ghost" onclick={() => (view = 'attach')} disabled={busy}>Back</Button>
				{#if !fineGrained}
					<Button onclick={() => void createPlan()} disabled={busy}>
						{busy ? 'Creating…' : 'Create plan & continue'}
					</Button>
				{:else}
					<span></span>
				{/if}
			{:else if view === 'finish'}
				<Button variant="outline" onclick={() => void exposeLater()} disabled={busy}>
					Exit &amp; expose later
				</Button>
				<Button onclick={() => void exposeNow()} disabled={busy}>
					{busy ? 'Exposing…' : 'Expose now'}
				</Button>
			{/if}
		</div>
	</Dialog.Content>
</Dialog.Root>














