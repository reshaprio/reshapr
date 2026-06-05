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
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import JsonBlock from '$lib/components/JsonBlock.svelte';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '$lib/serviceContext.js';
	import { Button } from '$lib/components/ui/button/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	let data = $state<unknown[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);

	async function load() {
		if (!ctx.id) return;
		loading = true;
		error = null;
		try {
			const list = await apiClient().listArtifactsByService(ctx.id);
			data = Array.isArray(list) ? list : [];
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			data = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading) void load();
	});
</script>

<div class="mb-4 flex items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">Artifacts</h3>
	<Button variant="outline" size="sm" disabled={loading} onclick={() => void load()}>Refresh</Button>
</div>

<p class="text-muted-foreground mb-4 text-sm">
	Import and attach flows are available under
	<a href="/artifacts" class="text-primary hover:underline">Experimental → Artifacts</a>.
</p>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<JsonBlock value={data} {loading} />

{#if !loading && !error && data.length === 0}
	<p class="text-muted-foreground mt-4 text-sm">No artifacts for this service.</p>
{/if}
