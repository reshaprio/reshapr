<script lang="ts">
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import { formatRelativeAge } from '$lib/utils/relativeAge.js';

	type ServiceRow = {
		id: string;
		name: string;
		version: string;
		type: string;
		age: string;
	};

	let rows = $state<ServiceRow[]>([]);
	let error = $state<string | null>(null);

	function pickType(raw: unknown): string {
		if (raw == null) return '—';
		if (typeof raw === 'string') return raw;
		return String(raw);
	}

	function toServiceRow(raw: unknown): ServiceRow | null {
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
			name: typeof o.name === 'string' ? o.name : '—',
			version: typeof o.version === 'string' ? o.version : '—',
			type: pickType(o.type),
			age: formatRelativeAge(created)
		};
	}

	async function load() {
		error = null;
		try {
			const data = (await apiClient().listServices()) as unknown[];
			const list = Array.isArray(data) ? data : [];
			rows = list.map(toServiceRow).filter((r): r is ServiceRow => r != null);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	$effect(() => {
		void load();
	});
</script>

<svelte:head>
	<title>Services — reShapr</title>
</svelte:head>

<PageHeader title="Services">
	{#snippet actions()}
		<Button variant="outline" onclick={() => void load()}>Refresh</Button>
	{/snippet}
</PageHeader>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<div class="rounded-lg border">
	<Table.Root>
		<Table.Header>
			<Table.Row>
				<Table.Head>ID</Table.Head>
				<Table.Head>NAME</Table.Head>
				<Table.Head>VERSION</Table.Head>
				<Table.Head>TYPE</Table.Head>
				<Table.Head>AGE</Table.Head>
				<Table.Head class="w-[100px]" />
			</Table.Row>
		</Table.Header>
		<Table.Body>
			{#each rows as s (s.id)}
				<Table.Row>
					<Table.Cell><code class="text-xs">{s.id}</code></Table.Cell>
					<Table.Cell class="font-medium">{s.name}</Table.Cell>
					<Table.Cell>{s.version}</Table.Cell>
					<Table.Cell>{s.type}</Table.Cell>
					<Table.Cell>{s.age}</Table.Cell>
					<Table.Cell>
						<Button variant="outline" size="sm" href="/services/{s.id}">Open</Button>
					</Table.Cell>
				</Table.Row>
			{/each}
		</Table.Body>
	</Table.Root>
</div>

{#if rows.length === 0 && !error}
	<p class="text-muted-foreground mt-4 text-sm">No services.</p>
{/if}
