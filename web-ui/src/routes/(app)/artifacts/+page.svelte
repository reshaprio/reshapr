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
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import * as Alert from '$lib/components/ui/alert/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Checkbox } from '$lib/components/ui/checkbox/index.js';
	import * as Collapsible from '$lib/components/ui/collapsible/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { parseOperationsList } from '$lib/operationsList.js';

	type Api = ReturnType<typeof apiClient>;

	type ExposeOptions = {
		backendEndpoint: string;
		gatewayGroupId: string;
		backendSecretId?: string;
		genApiKey: boolean;
		includedOperations?: string[];
		excludedOperations?: string[];
	};

	type ExposeResult = {
		serviceId: string;
		serviceName: string;
		planId: string;
		expoId?: string;
		planApiKey?: string;
	};

	const DEFAULT_OPEN_METEO_URL =
		'https://raw.githubusercontent.com/open-meteo/open-meteo/refs/heads/main/openapi.yml';
	const DEFAULT_OPEN_METEO_BACKEND = 'https://api.open-meteo.com';

	let msg = $state<string | null>(null);
	let err = $state<string | null>(null);
	let importServiceApiKey = $state<string | null>(null);
	let importSource = $state<'file' | 'url'>('file');
	let genKeyImport = $state(false);

	let importExposeOpen = $state(true);
	let importOnlyOpen = $state(false);
	let attachCustomToolsOpen = $state(false);
	let attachPromptsOpen = $state(false);
	let advancedAttachOpen = $state(false);
	let specUrl = $state(DEFAULT_OPEN_METEO_URL);
	let backendEndpointExpose = $state('');
	let includedOperationsExpose = $state('');

	$effect(() => {
		if (importSource === 'url') {
			backendEndpointExpose = DEFAULT_OPEN_METEO_BACKEND;
		} else {
			backendEndpointExpose = '';
		}
	});

	function asImportedService(v: unknown): { id: string; name: string } | null {
		if (!v || typeof v !== 'object') return null;
		const o = v as Record<string, unknown>;
		if (typeof o.id !== 'string' || typeof o.name !== 'string') return null;
		return { id: o.id, name: o.name };
	}

	async function createPlanAndExposition(
		c: Api,
		imported: unknown,
		opts: ExposeOptions
	): Promise<ExposeResult> {
		const service = asImportedService(imported);
		if (!service) {
			throw new Error('Unexpected import response: service id or name is missing.');
		}

		const planBody: Record<string, unknown> = {
			name: `default-plan for ${service.name}`,
			description: `Configuration plan for ${service.name} on ${opts.backendEndpoint}`,
			serviceId: service.id,
			backendEndpoint: opts.backendEndpoint,
			backendSecretId: opts.backendSecretId
		};
		if (opts.genApiKey) planBody.apiKey = 'generate-me';
		if (opts.includedOperations?.length) planBody.includedOperations = opts.includedOperations;
		if (opts.excludedOperations?.length) planBody.excludedOperations = opts.excludedOperations;

		const plan = (await c.createConfigurationPlan(planBody)) as { id: string; apiKey?: string };
		const expo = (await c.createExposition({
			configurationPlanId: plan.id,
			gatewayGroupId: opts.gatewayGroupId
		})) as { id?: string };

		return {
			serviceId: service.id,
			serviceName: service.name,
			planId: plan.id,
			expoId: expo.id,
			planApiKey: plan.apiKey
		};
	}

	function clearAlerts() {
		err = null;
		msg = null;
		importServiceApiKey = null;
	}

	function formatAttachSuccess(out: unknown): string {
		if (out && typeof out === 'object') {
			const o = out as Record<string, unknown>;
			const name = typeof o.name === 'string' ? o.name : undefined;
			const type = typeof o.type === 'string' ? o.type : undefined;
			const id = typeof o.id === 'string' ? o.id : undefined;
			const bits = ['Attach OK'];
			if (type) bits.push(`type ${type}`);
			if (name) bits.push(`« ${name} »`);
			if (id) bits.push(`id ${id}`);
			return bits.join(' — ');
		}
		return `Attach OK : ${JSON.stringify(out)}`;
	}

	async function onImportAndExpose(ev: SubmitEvent) {
		ev.preventDefault();
		const form = ev.target as HTMLFormElement;
		clearAlerts();
		const fd = new FormData(form);

		const backendEndpoint = String(fd.get('backendEndpoint') || '').trim();
		if (!backendEndpoint) {
			err = 'Target backend URL (--backendEndpoint) is required.';
			return;
		}
		const sn = String(fd.get('serviceNameIs') || '').trim();
		const sv = String(fd.get('serviceVersionIs') || '').trim();
		if ((sn && !sv) || (!sn && sv)) {
			err = 'For GraphQL: set both serviceName and serviceVersion, or leave both empty.';
			return;
		}
		const gatewayGroupId = String(fd.get('gatewayGroupId') || '').trim() || '1';
		const backendSecretId = String(fd.get('backendSecretId') || '').trim() || undefined;
		let includedOperations: string[] = [];
		try {
			includedOperations = parseOperationsList(includedOperationsExpose);
		} catch (e) {
			err = e instanceof Error ? e.message : String(e);
			return;
		}

		const extra: Record<string, string> = {};
		if (sn) extra.serviceName = sn;
		if (sv) extra.serviceVersion = sv;

		try {
			const c = apiClient();
			let imported: unknown;

			if (importSource === 'file') {
				const file = fd.get('serviceSpecFile') as File | null;
				if (!file?.size) {
					err = 'Choose a specification file (-f / --file).';
					return;
				}
				imported = await c.importArtifactFile(file, extra);
			} else {
				const url = String(fd.get('specUrl') || '').trim();
				if (!url) {
					err = 'Specification URL is required (-u / --url).';
					return;
				}
				const p = new URLSearchParams();
				p.set('url', url);
				p.set('mainArtifact', 'true');
				const secret = String(fd.get('secretName') || '').trim();
				if (secret) p.set('secretName', secret);
				if (sn) p.set('serviceName', sn);
				if (sv) p.set('serviceVersion', sv);
				imported = await c.importArtifactUrl(p);
			}

			const out = await createPlanAndExposition(c, imported, {
				backendEndpoint,
				gatewayGroupId,
				backendSecretId,
				genApiKey: genKeyImport,
				includedOperations: includedOperations.length ? includedOperations : undefined
			});
			if (out.planApiKey) importServiceApiKey = out.planApiKey;
			const via = importSource === 'file' ? '-f' : '-u';
			msg =
				`Import + exposition OK (${via}, --backendEndpoint) — service "${out.serviceName}" (${out.serviceId}), plan ${out.planId}` +
				(out.expoId ? `, exposition ${out.expoId}.` : '.');
			form.reset();
			genKeyImport = false;
		} catch (e) {
			err = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onImportFile(ev: SubmitEvent) {
		ev.preventDefault();
		const form = ev.target as HTMLFormElement;
		err = null;
		msg = null;
		importServiceApiKey = null;
		const fd = new FormData(form);
		const file = fd.get('file') as File | null;
		if (!file?.size) {
			err = 'Choose a file.';
			return;
		}
		const sn = String(fd.get('serviceName') || '').trim();
		const sv = String(fd.get('serviceVersion') || '').trim();
		const extra: Record<string, string> = {};
		if (sn) extra.serviceName = sn;
		if (sv) extra.serviceVersion = sv;
		try {
			const out = await apiClient().importArtifactFile(file, extra);
			msg = `Import OK : ${JSON.stringify(out)}`;
			form.reset();
		} catch (e) {
			err = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onImportUrl(ev: SubmitEvent) {
		ev.preventDefault();
		const form = ev.target as HTMLFormElement;
		err = null;
		msg = null;
		importServiceApiKey = null;
		const fd = new FormData(form);
		const url = String(fd.get('url') || '');
		if (!url) {
			err = 'URL is required.';
			return;
		}
		const p = new URLSearchParams();
		p.set('url', url);
		p.set('mainArtifact', 'true');
		const secret = String(fd.get('secretName') || '');
		if (secret) p.set('secretName', secret);
		const sn = String(fd.get('serviceName') || '');
		const sv = String(fd.get('serviceVersion') || '');
		if (sn) p.set('serviceName', sn);
		if (sv) p.set('serviceVersion', sv);
		try {
			const out = await apiClient().importArtifactUrl(p);
			msg = `Import URL OK : ${JSON.stringify(out)}`;
			form.reset();
		} catch (e) {
			err = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onAttachFile(ev: SubmitEvent) {
		ev.preventDefault();
		const form = ev.target as HTMLFormElement;
		err = null;
		msg = null;
		importServiceApiKey = null;
		const fd = new FormData(form);
		const file = fd.get('afile') as File | null;
		if (!file?.size) {
			err = 'Choose a file.';
			return;
		}
		try {
			const out = await apiClient().attachArtifactFile(file);
			msg = formatAttachSuccess(out);
			form.reset();
		} catch (e) {
			err = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onAttachUrl(ev: SubmitEvent) {
		ev.preventDefault();
		const form = ev.target as HTMLFormElement;
		err = null;
		msg = null;
		importServiceApiKey = null;
		const fd = new FormData(form);
		const url = String(fd.get('aurl') || '');
		const secret = String(fd.get('asecret') || '');
		if (!url) {
			err = 'URL is required.';
			return;
		}
		try {
			const out = await apiClient().attachArtifactUrl(url, secret || undefined);
			msg = formatAttachSuccess(out);
			form.reset();
		} catch (e) {
			err = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<PageHeader title="Artifacts" />

<Alert.Root class="mb-4">
	<Alert.Title>MCP server creation (recommended order)</Alert.Title>
	<Alert.Description class="space-y-2 text-sm">
		<ol class="list-decimal space-y-1 pl-5">
			<li><strong>Import + expose</strong> — spec, backend URL, optional <code class="text-xs">--io</code>.</li>
			<li><strong>Import only</strong> — if you split plan/exposition on <a href="/plans/new" class="text-primary hover:underline">Plans</a>.</li>
			<li><strong>Custom tools</strong> — YAML <code class="text-xs">kind: CustomTools</code>.</li>
			<li><strong>MCP prompts</strong> — YAML <code class="text-xs">kind: Prompts</code>.</li>
			<li>
				<strong>Verify</strong> —
				<a href="/mcp-custom-tools" class="text-primary hover:underline">MCP custom tools</a>,
				<a href="/mcp-prompts" class="text-primary hover:underline">MCP prompts</a>.
			</li>
		</ol>
		<p class="text-muted-foreground">
			Full guide: <code class="text-xs">docs/MCP_SERVER_SETUP.md</code> in this repository.
		</p>
	</Alert.Description>
</Alert.Root>

{#if err}
	<ApiErrorAlert message={err} />
{/if}
{#if msg}
	<Alert.Root class="mb-4 border-green-600/30">
		<Alert.Title>Success</Alert.Title>
		<Alert.Description>{msg}</Alert.Description>
	</Alert.Root>
{/if}
{#if importServiceApiKey}
	<Alert.Root class="mb-4">
		<Alert.Title>Plan API key (copy once)</Alert.Title>
		<Alert.Description>
			<code class="text-xs break-all">{importServiceApiKey}</code>
			<p class="text-muted-foreground mt-2 text-xs">Save it now; it will not be shown again.</p>
		</Alert.Description>
	</Alert.Root>
{/if}

<Collapsible.Root bind:open={importExposeOpen} class="mb-4">
	<Card.Root>
		<Collapsible.Trigger class="w-full text-left">
			<Card.Header>
				<Card.Title class="text-base">1. Import + expose (spec, plan, exposition)</Card.Title>
				<Card.Description>
					Like <code class="text-xs">reshapr import -f|-u … --backendEndpoint …</code> then plan + exposition.
					Optional <code class="text-xs">--io</code> below. Needs a
					<a href="/gateway-groups" class="text-primary hover:underline">gateway group</a>.
				</Card.Description>
			</Card.Header>
		</Collapsible.Trigger>
		<Collapsible.Content>
			<Card.Content>
				<form class="space-y-4" onsubmit={onImportAndExpose}>
					<div class="space-y-2">
						<span class="text-sm font-medium">Specification source</span>
						<div class="flex flex-wrap gap-4">
							<label class="flex items-center gap-2 text-sm">
								<input
									type="radio"
									name="importSourceUi"
									checked={importSource === 'file'}
									onchange={() => (importSource = 'file')}
								/>
								File (<code class="text-xs">-f</code>)
							</label>
							<label class="flex items-center gap-2 text-sm">
								<input
									type="radio"
									name="importSourceUi"
									checked={importSource === 'url'}
									onchange={() => (importSource = 'url')}
								/>
								URL (<code class="text-xs">-u</code>)
							</label>
						</div>
					</div>

					{#if importSource === 'file'}
						<div class="space-y-2">
							<Label for="serviceSpecFile">Specification file</Label>
							<Input id="serviceSpecFile" type="file" name="serviceSpecFile" />
						</div>
					{:else}
						<div class="space-y-2">
							<Label for="specUrl">Specification URL</Label>
							<Input
								id="specUrl"
								name="specUrl"
								class="w-full"
								bind:value={specUrl}
								placeholder="https://…"
								autocomplete="off"
							/>
						</div>
						<div class="space-y-2">
							<Label for="secretName">Secret to fetch the spec (optional)</Label>
							<Input id="secretName" name="secretName" autocomplete="off" />
						</div>
					{/if}

					{#key importSource}
						<div class="space-y-2">
							<Label for="backendEndpoint">Backend endpoint (<code class="text-xs">--backendEndpoint</code>)</Label>
							<Input
								id="backendEndpoint"
								name="backendEndpoint"
								class="w-full"
								placeholder="https://…"
								required
								bind:value={backendEndpointExpose}
								autocomplete="off"
							/>
						</div>
					{/key}
					<div class="space-y-2">
						<Label for="gatewayGroupId">Gateway group ID (default: 1)</Label>
						<Input id="gatewayGroupId" name="gatewayGroupId" placeholder="1" value="1" autocomplete="off" />
					</div>
					<div class="space-y-2">
						<Label for="backendSecretId">Backend secret ID (optional)</Label>
						<Input id="backendSecretId" name="backendSecretId" autocomplete="off" />
					</div>
					<div class="space-y-2">
						<Label for="serviceNameIs">serviceName (GraphQL, optional)</Label>
						<Input id="serviceNameIs" name="serviceNameIs" autocomplete="off" />
					</div>
					<div class="space-y-2">
						<Label for="serviceVersionIs">serviceVersion (GraphQL, optional)</Label>
						<Input id="serviceVersionIs" name="serviceVersionIs" autocomplete="off" />
					</div>
					<div class="space-y-2">
						<Label for="includedOperationsExpose">Included operations (<code class="text-xs">--io</code>, optional)</Label>
						<Textarea
							id="includedOperationsExpose"
							bind:value={includedOperationsExpose}
							rows={3}
							class="font-mono text-xs"
							placeholder={'POST /tests/{testId}/start\nGET /masters'}
						/>
					</div>
					<div class="flex items-center gap-2">
						<Checkbox id="apiKeyIs" bind:checked={genKeyImport} />
						<Label for="apiKeyIs">Generate an API key on the plan</Label>
					</div>
					<Button type="submit">Import and expose</Button>
				</form>
			</Card.Content>
		</Collapsible.Content>
	</Card.Root>
</Collapsible.Root>

<Collapsible.Root bind:open={importOnlyOpen} class="mb-4">
	<Card.Root>
		<Collapsible.Trigger class="w-full text-left">
			<Card.Header>
				<Card.Title class="text-base">2. Import specification only (split workflow)</Card.Title>
				<Card.Description>
					<code class="text-xs">POST /api/v1/artifacts</code> without plan or exposition — then
					<a href="/plans/new" class="text-primary hover:underline">create a plan</a> with
					<code class="text-xs">--io</code> and an exposition.
				</Card.Description>
			</Card.Header>
		</Collapsible.Trigger>
		<Collapsible.Content>
			<Card.Content class="space-y-6">
				<form class="flex flex-wrap items-end gap-3" onsubmit={onImportFile}>
					<Input type="file" name="file" required />
					<Input name="serviceName" placeholder="serviceName (GraphQL)" />
					<Input name="serviceVersion" placeholder="serviceVersion" />
					<Button type="submit" variant="secondary">Import file</Button>
				</form>
				<form class="flex flex-wrap items-end gap-3 border-t pt-4" onsubmit={onImportUrl}>
					<Input name="url" placeholder="https://…" class="min-w-[200px] flex-1" required />
					<Input name="secretName" placeholder="secretName (optional)" />
					<Input name="serviceName" placeholder="serviceName" />
					<Input name="serviceVersion" placeholder="serviceVersion" />
					<Button type="submit" variant="secondary">Import URL</Button>
				</form>
			</Card.Content>
		</Collapsible.Content>
	</Card.Root>
</Collapsible.Root>

<Collapsible.Root bind:open={attachCustomToolsOpen} class="mb-4">
	<Card.Root>
		<Collapsible.Trigger class="w-full text-left">
			<Card.Header>
				<Card.Title class="text-base">3. Attach custom tools (YAML)</Card.Title>
				<Card.Description>
					Like <code class="text-xs">reshapr attach -f custom-tools.yaml</code> —
					<code class="text-xs">POST /api/v1/artifacts/attach</code>. Document
					<code class="text-xs">kind: CustomTools</code> (schema
					<code class="text-xs">CustomTools-v1alpha1</code>) bound to an existing service. Example in the
					<a
						href="https://github.com/reshaprio/reshapr/blob/main/dev/github-api-custom-tools.yaml"
						target="_blank"
						rel="noreferrer"
						class="text-primary hover:underline"
					>reshapr repo</a>.
				</Card.Description>
			</Card.Header>
		</Collapsible.Trigger>
		<Collapsible.Content>
			<Card.Content class="space-y-4">
				<form class="flex flex-wrap items-end gap-3" onsubmit={onAttachFile}>
					<div class="min-w-[200px] flex-1 space-y-2">
						<Label for="customToolsFile">Custom tools file</Label>
						<Input id="customToolsFile" type="file" name="afile" accept=".yaml,.yml,.json" required />
					</div>
					<Button type="submit">Attach file</Button>
				</form>
				<form class="flex flex-wrap items-end gap-3 border-t pt-4" onsubmit={onAttachUrl}>
					<div class="min-w-[200px] flex-1 space-y-2">
						<Label for="customToolsUrl">Or URL</Label>
						<Input id="customToolsUrl" name="aurl" placeholder="https://…" class="w-full" required />
					</div>
					<div class="space-y-2">
						<Label for="customToolsSecret">Secret (optional)</Label>
						<Input id="customToolsSecret" name="asecret" placeholder="secretName" />
					</div>
					<Button type="submit" variant="secondary">Attach URL</Button>
				</form>
			</Card.Content>
		</Collapsible.Content>
	</Card.Root>
</Collapsible.Root>

<Collapsible.Root bind:open={attachPromptsOpen} class="mb-4">
	<Card.Root>
		<Collapsible.Trigger class="w-full text-left">
			<Card.Header>
				<Card.Title class="text-base">4. Attach MCP prompts (YAML)</Card.Title>
				<Card.Description>
					Same endpoint as custom tools: <code class="text-xs">reshapr attach -f prompts.yaml</code> →
					<code class="text-xs">POST /api/v1/artifacts/attach</code>. Document
					<code class="text-xs">kind: Prompts</code> (<code class="text-xs">Prompts-v1alpha1</code>) with
					<code class="text-xs">service.name</code> / <code class="text-xs">service.version</code> matching the
					imported service. Example:
					<a
						href="https://github.com/reshaprio/reshapr/blob/main/dev/apipastry-prompts.yaml"
						target="_blank"
						rel="noreferrer"
						class="text-primary hover:underline"
					>apipastry-prompts.yaml</a>.
				</Card.Description>
			</Card.Header>
		</Collapsible.Trigger>
		<Collapsible.Content>
			<Card.Content class="space-y-4">
				<p class="text-muted-foreground text-xs">
					Minimal shape: <code class="text-xs">apiVersion: reshapr.io/v1alpha1</code>,
					<code class="text-xs">kind: Prompts</code>, <code class="text-xs">service</code>,
					<code class="text-xs">prompts:</code> map of prompt definitions. Verify on <a href="/mcp-prompts" class="text-primary hover:underline">MCP prompts</a> (control-plane artifact, no CORS).
				</p>
				<form class="flex flex-wrap items-end gap-3" onsubmit={onAttachFile}>
					<div class="min-w-[200px] flex-1 space-y-2">
						<Label for="promptsFile">Prompts file</Label>
						<Input id="promptsFile" type="file" name="afile" accept=".yaml,.yml" required />
					</div>
					<Button type="submit">Attach file</Button>
				</form>
				<form class="flex flex-wrap items-end gap-3 border-t pt-4" onsubmit={onAttachUrl}>
					<div class="min-w-[200px] flex-1 space-y-2">
						<Label for="promptsUrl">Or URL</Label>
						<Input id="promptsUrl" name="aurl" placeholder="https://…" class="w-full" required />
					</div>
					<div class="space-y-2">
						<Label for="promptsSecret">Secret (optional)</Label>
						<Input id="promptsSecret" name="asecret" placeholder="secretName" />
					</div>
					<Button type="submit" variant="secondary">Attach URL</Button>
				</form>
			</Card.Content>
		</Collapsible.Content>
	</Card.Root>
</Collapsible.Root>

<Card.Root class="mb-4">
	<Card.Header>
		<Card.Title class="text-base">5. Verify MCP</Card.Title>
		<Card.Description>After steps 1–4, confirm tools and prompts on the exposition.</Card.Description>
	</Card.Header>
	<Card.Content class="flex flex-wrap gap-2">
		<Button variant="outline" href="/services">Services</Button>
		<Button variant="outline" href="/plans">Plans</Button>
		<Button variant="outline" href="/expositions">Expositions</Button>
		<Button variant="outline" href="/mcp-custom-tools">MCP custom tools</Button>
		<Button variant="outline" href="/mcp-prompts">MCP prompts</Button>
	</Card.Content>
</Card.Root>

<Collapsible.Root bind:open={advancedAttachOpen} class="mb-4">
	<Card.Root>
		<Collapsible.Trigger class="w-full text-left">
			<Card.Header>
				<Card.Title class="text-base">Advanced — generic attach</Card.Title>
				<Card.Description>POST /api/v1/artifacts/attach for any artifact type (file or URL).</Card.Description>
			</Card.Header>
		</Collapsible.Trigger>
		<Collapsible.Content>
			<Card.Content class="space-y-4">
				<form class="flex flex-wrap items-end gap-3" onsubmit={onAttachFile}>
					<Input type="file" name="afile" required />
					<Button type="submit" variant="secondary">Attach file</Button>
				</form>
				<form class="flex flex-wrap items-end gap-3 border-t pt-4" onsubmit={onAttachUrl}>
					<Input name="aurl" placeholder="https://…" class="min-w-[200px] flex-1" required />
					<Input name="asecret" placeholder="secretName (optional)" />
					<Button type="submit" variant="secondary">Attach URL</Button>
				</form>
			</Card.Content>
		</Collapsible.Content>
	</Card.Root>
</Collapsible.Root>
