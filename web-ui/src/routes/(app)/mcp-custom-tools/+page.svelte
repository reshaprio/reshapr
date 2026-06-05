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
	import ScrollableCode from '$lib/components/ScrollableCode.svelte';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import * as Collapsible from '$lib/components/ui/collapsible/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import {
		resolveMcpCustomToolsFromUrl,
		type McpCustomToolsResolution
	} from '$lib/mcpCustomTools.js';
	import { listMcpEndpointUrls, type McpUrlListItem } from '$lib/mcpEndpointUrls.js';

	let mcpUrl = $state('');
	let urlMode = $state<'active' | 'all'>('active');
	let urlList = $state<McpUrlListItem[]>([]);
	let urlListLoading = $state(false);
	let result = $state<McpCustomToolsResolution | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(false);
	let rawJsonOpen = $state(false);
	let yamlOpen = $state(true);

	const tools = $derived(result?.tools ?? []);

	async function runResolve(url: string) {
		const trimmed = url.trim();
		if (!trimmed) {
			error = 'MCP URL is empty';
			return;
		}
		mcpUrl = trimmed;
		error = null;
		result = null;
		loading = true;
		try {
			const c = apiClient();
			const payload = await resolveMcpCustomToolsFromUrl(trimmed, {
				listServicesPage: (page, size) => c.listServicesPage(page, size),
				listExpositionsActive: () => c.listExpositionsActive(),
				listExpositionsAll: () => c.listExpositionsAll(),
				getExposition: (id) => c.getExposition(id),
				listArtifactsByService: (serviceId) => c.listArtifactsByService(serviceId),
				getService: (id) => c.getService(id)
			});
			result = payload;
			yamlOpen = Boolean(payload.artifactYaml);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	function onSubmit(ev: SubmitEvent) {
		ev.preventDefault();
		void runResolve(mcpUrl);
	}

	async function loadMcpUrls() {
		error = null;
		urlListLoading = true;
		urlList = [];
		try {
			const c = apiClient();
			const items = await listMcpEndpointUrls(urlMode, {
				listExpositionsActive: () => c.listExpositionsActive(),
				listExpositionsAll: () => c.listExpositionsAll(),
				getActiveExpositionOrNull: (id) => c.getActiveExpositionOrNull(id)
			});
			urlList = items;
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			urlListLoading = false;
		}
	}

	function gatewayLabel(row: McpUrlListItem): string {
		return row.gatewayName ? `${row.gatewayName} · ${row.fqdn}` : row.fqdn;
	}
</script>

<PageHeader title="MCP — Custom tools" />

<Card.Root class="mb-6">
	<Card.Header>
		<Card.Title class="text-base">MCP URLs</Card.Title>
		<Card.Description>Derives URLs from gateway FQDNs returned by the API (active expositions).</Card.Description>
	</Card.Header>
	<Card.Content class="space-y-4">
		<div class="flex flex-wrap items-center gap-4">
			<label class="flex items-center gap-2 text-sm">
				<input
					type="radio"
					name="urlMode"
					checked={urlMode === 'active'}
					onchange={() => (urlMode = 'active')}
				/>
				Active only
			</label>
			<label class="flex items-center gap-2 text-sm">
				<input
					type="radio"
					name="urlMode"
					checked={urlMode === 'all'}
					onchange={() => (urlMode = 'all')}
				/>
				All expositions (active/&#123;id&#125; per id, 404s ignored)
			</label>
			<Button variant="outline" disabled={urlListLoading} onclick={() => void loadMcpUrls()}>
				{urlListLoading ? 'Loading…' : 'List MCP URLs'}
			</Button>
		</div>

		{#if urlList.length > 0}
			<div class="overflow-x-auto rounded-lg border">
				<Table.Root class="min-w-[52rem] table-fixed">
					<Table.Header>
						<Table.Row>
							<Table.Head class="w-[38%]">MCP URL</Table.Head>
							<Table.Head class="w-[12%]">Exposition</Table.Head>
							<Table.Head class="w-[14%]">Service</Table.Head>
							<Table.Head class="w-[24%]">Gateway / FQDN</Table.Head>
							<Table.Head class="w-[12%]">Actions</Table.Head>
						</Table.Row>
					</Table.Header>
					<Table.Body>
						{#each urlList as row (`${row.expositionId}-${row.url}`)}
							<Table.Row class="align-top">
								<Table.Cell class="py-2">
									<ScrollableCode text={row.url} maxHeight="4.5rem" />
								</Table.Cell>
								<Table.Cell class="py-2">
									<code class="text-xs break-all">{row.expositionId}</code>
								</Table.Cell>
								<Table.Cell class="py-2 text-sm">
									{row.serviceName}:{row.serviceVersion}
								</Table.Cell>
								<Table.Cell class="py-2">
									<ScrollableCode text={gatewayLabel(row)} maxHeight="4.5rem" />
								</Table.Cell>
								<Table.Cell class="py-2">
									<div class="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
										<Button variant="outline" size="sm" onclick={() => (mcpUrl = row.url)}>
											Use
										</Button>
										<Button size="sm" disabled={loading} onclick={() => void runResolve(row.url)}>
											Custom tools
										</Button>
									</div>
								</Table.Cell>
							</Table.Row>
						{/each}
					</Table.Body>
				</Table.Root>
			</div>
		{/if}

		{#if !urlListLoading && urlList.length === 0}
			<p class="text-muted-foreground text-sm">Click &quot;List MCP URLs&quot; to populate the table.</p>
		{/if}
	</Card.Content>
</Card.Root>

<Card.Root class="mb-6">
	<Card.Header>
		<Card.Title class="text-base">Custom tools resolution</Card.Title>
		<Card.Description>
			From path <code class="text-xs">/mcp/&#123;org&#125;/&#123;service&#125;/&#123;version&#125;</code>, calls control-plane APIs then optionally
			<code class="text-xs">/api/v1/services/&#123;id&#125;</code>.
		</Card.Description>
	</Card.Header>
	<Card.Content>
		<form class="space-y-4" onsubmit={onSubmit}>
			<div class="space-y-2">
				<Label for="mcp-url">MCP URL</Label>
				<Textarea
					id="mcp-url"
					class="min-h-[2.75rem] resize-y font-mono text-xs break-all"
					rows={2}
					bind:value={mcpUrl}
					placeholder="http://host:port/mcp/org/service/version"
					autocomplete="off"
				/>
			</div>
			<Button type="submit" disabled={loading}>
				{loading ? 'Resolving…' : 'Resolve custom tools'}
			</Button>
		</form>
	</Card.Content>
</Card.Root>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

{#if result !== null}
	<Card.Root class="overflow-hidden">
		<Card.Header>
			<Card.Title class="text-base">Resolution result</Card.Title>
			<Card.Description>
				{result.source === 'artifacts_custom_tools'
					? 'Source: RESHAPR_CUSTOM_TOOLS artifact YAML (filtered by includedOperations).'
					: 'Source: service operations (intersection with includedOperations).'}
				Exposition <code class="text-xs">{result.expoId}</code>, service
				<code class="text-xs">{result.serviceId}</code>
			</Card.Description>
		</Card.Header>
		<Card.Content class="space-y-4">
			{#if tools.length > 0}
				<div class="flex flex-wrap gap-2">
					{#each tools as t (t.name)}
						<Badge variant="outline">{t.name}</Badge>
					{/each}
				</div>

				<div class="overflow-x-auto rounded-lg border">
					<Table.Root>
						<Table.Header>
							<Table.Row>
								<Table.Head class="w-40">Name</Table.Head>
								<Table.Head>Description</Table.Head>
							</Table.Row>
						</Table.Header>
						<Table.Body>
							{#each tools as t (t.name)}
								<Table.Row class="align-top">
									<Table.Cell class="py-2 font-mono text-xs">{t.name}</Table.Cell>
									<Table.Cell class="py-2">
										{#if t.description}
											<ScrollableCode text={t.description} maxHeight="4rem" />
										{:else}
											<span class="text-muted-foreground text-xs">—</span>
										{/if}
									</Table.Cell>
								</Table.Row>
							{/each}
						</Table.Body>
					</Table.Root>
				</div>
			{:else}
				<p class="text-muted-foreground text-sm">No custom tools after filtering.</p>
			{/if}

			{#if result.artifactYaml}
				<Collapsible.Root bind:open={yamlOpen} class="rounded-lg border">
					<Collapsible.Trigger
						class="hover:bg-muted/50 flex w-full items-center justify-between px-4 py-3 text-left text-sm font-medium"
						type="button"
					>
						RESHAPR_CUSTOM_TOOLS YAML (artifact source)
						<span class="text-muted-foreground text-xs font-normal">
							{yamlOpen ? 'Hide' : 'Show'}
						</span>
					</Collapsible.Trigger>
					<Collapsible.Content class="border-t px-2 pb-2">
						<ScrollableCode text={result.artifactYaml} maxHeight="min(70vh, 28rem)" class="border-0" />
					</Collapsible.Content>
				</Collapsible.Root>
			{/if}

			<Collapsible.Root bind:open={rawJsonOpen} class="rounded-lg border">
				<Collapsible.Trigger
					class="hover:bg-muted/50 flex w-full items-center justify-between px-4 py-3 text-left text-sm font-medium"
					type="button"
				>
					Raw JSON (full payload)
					<span class="text-muted-foreground text-xs font-normal">
						{rawJsonOpen ? 'Hide' : 'Show'}
					</span>
				</Collapsible.Trigger>
				<Collapsible.Content class="border-t p-2">
					<ScrollableCode
						text={JSON.stringify(result, null, 2)}
						maxHeight="min(50vh, 20rem)"
						class="border-0"
					/>
				</Collapsible.Content>
			</Collapsible.Root>
		</Card.Content>
	</Card.Root>
{/if}
