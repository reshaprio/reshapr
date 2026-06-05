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
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import { formatRelativeAge } from '$lib/utils/relativeAge.js';

	type ExpoRow = {
		id: string;
		service: string;
		backend: string;
		endpoints: string;
		age: string;
	};

	let mode = $state<'active' | 'all'>('active');
	let rows = $state<ExpoRow[]>([]);
	let error = $state<string | null>(null);
	let planId = $state('1');
	let ggId = $state('1');

	function serviceLabel(service: unknown): string {
		if (!service || typeof service !== 'object') return '—';
		const s = service as Record<string, unknown>;
		const name = typeof s.name === 'string' ? s.name : '';
		const version = typeof s.version === 'string' ? s.version : '';
		if (name && version) return `${name}:${version}`;
		if (name) return name;
		return '—';
	}

	function backendUrl(configurationPlan: unknown): string {
		if (!configurationPlan || typeof configurationPlan !== 'object') return '—';
		const c = configurationPlan as Record<string, unknown>;
		return typeof c.backendEndpoint === 'string' ? c.backendEndpoint : '—';
	}

	function endpointsLabel(raw: Record<string, unknown>): string {
		const cp = raw.configurationPlan;
		if (cp && typeof cp === 'object') {
			const c = cp as Record<string, unknown>;
			const inc = c.includedOperations;
			if (Array.isArray(inc)) return String(inc.length);
		}
		const gws = raw.gateways;
		if (Array.isArray(gws)) {
			let n = 0;
			for (const g of gws) {
				if (g && typeof g === 'object') {
					const fq = (g as Record<string, unknown>).fqdns;
					if (Array.isArray(fq)) n += fq.length;
				}
			}
			if (n > 0) return String(n);
		}
		return '—';
	}

	function toExpoRow(raw: unknown): ExpoRow | null {
		if (!raw || typeof raw !== 'object') return null;
		const o = raw as Record<string, unknown>;
		if (typeof o.id !== 'string') return null;
		const created =
			typeof o.createdOn === 'string'
				? o.createdOn
				: typeof o.created === 'string'
					? o.created
					: undefined;
		return {
			id: o.id,
			service: serviceLabel(o.service),
			backend: backendUrl(o.configurationPlan),
			endpoints: endpointsLabel(o),
			age: formatRelativeAge(created)
		};
	}

	async function load() {
		error = null;
		try {
			const data =
				mode === 'active'
					? ((await apiClient().listExpositionsActive()) as unknown[])
					: ((await apiClient().listExpositionsAll()) as unknown[]);
			const list = Array.isArray(data) ? data : [];
			rows = list.map(toExpoRow).filter((r): r is ExpoRow => r != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	$effect(() => {
		mode;
		void load();
	});

	async function onCreate(ev: SubmitEvent) {
		ev.preventDefault();
		error = null;
		try {
			await apiClient().createExposition({
				configurationPlanId: planId.trim(),
				gatewayGroupId: ggId.trim()
			});
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<PageHeader title="Expositions">
	{#snippet actions()}
		<div class="flex flex-wrap items-center gap-4">
			<label class="flex items-center gap-2 text-sm">
				<input type="radio" name="m" checked={mode === 'active'} onchange={() => (mode = 'active')} />
				Active
			</label>
			<label class="flex items-center gap-2 text-sm">
				<input type="radio" name="m" checked={mode === 'all'} onchange={() => (mode = 'all')} />
				All
			</label>
			<Button variant="outline" onclick={() => void load()}>Refresh</Button>
		</div>
	{/snippet}
</PageHeader>

<Card.Root class="mb-6">
	<Card.Header>
		<Card.Title>Create exposition</Card.Title>
		<Card.Description>
			The client sends <code class="text-xs">POST /api/v1/expositions</code> with a JSON body that only
			includes two required properties:
		</Card.Description>
	</Card.Header>
	<Card.Content class="space-y-4">
		<ul class="text-muted-foreground list-inside list-disc text-sm">
			<li>
				<code class="text-xs">configurationPlanId</code> — id of the configuration plan (see
				<a href="/plans" class="text-primary hover:underline">Plans</a>).
			</li>
			<li>
				<code class="text-xs">gatewayGroupId</code> — id of the gateway group (see
				<a href="/gateway-groups" class="text-primary hover:underline">Gateway groups</a>).
			</li>
		</ul>
		<p class="text-muted-foreground text-sm">
			Other server-side DTO fields are not entered here: the control plane sets them on create.
		</p>
		<form class="space-y-4" onsubmit={onCreate}>
			<div class="space-y-2">
				<Label for="expo-configurationPlanId"><code class="text-xs">configurationPlanId</code></Label>
				<Input
					id="expo-configurationPlanId"
					class="w-full"
					bind:value={planId}
					placeholder="Plan UUID or id"
					autocomplete="off"
					spellcheck={false}
				/>
			</div>
			<div class="space-y-2">
				<Label for="expo-gatewayGroupId"><code class="text-xs">gatewayGroupId</code></Label>
				<Input
					id="expo-gatewayGroupId"
					class="w-full"
					bind:value={ggId}
					placeholder="Gateway group UUID or id"
					autocomplete="off"
					spellcheck={false}
				/>
			</div>
			<Button type="submit">Create</Button>
		</form>
	</Card.Content>
</Card.Root>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>SERVICE</Table.Head>
				<Table.Head>BACKEND</Table.Head>
				<Table.Head>ENDPOINTS</Table.Head>
				<Table.Head>AGE</Table.Head>
				<Table.Head class="w-[100px]" />
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#each rows as x (x.id)}
				<Table.Row>
					<Table.Cell><code class="text-xs">{x.id}</code></Table.Cell>
					<Table.Cell class="max-w-[200px] truncate" title={x.service}>{x.service}</Table.Cell>
					<Table.Cell class="max-w-[200px] truncate" title={x.backend}>{x.backend}</Table.Cell>
					<Table.Cell>{x.endpoints}</Table.Cell>
					<Table.Cell>{x.age}</Table.Cell>
					<Table.Cell>
						<Button variant="outline" size="sm" href="/expositions/{x.id}">Details</Button>
					</Table.Cell>
				</Table.Row>
			{/each}
		</Table.Body>
	</Table.Root>
</div>

{#if rows.length === 0 && !error}
	<p class="text-muted-foreground mt-4 text-sm">No expositions.</p>
{/if}
