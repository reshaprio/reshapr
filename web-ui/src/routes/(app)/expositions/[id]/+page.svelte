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
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import JsonBlock from '$lib/components/JsonBlock.svelte';
	import { Button } from '$lib/components/ui/button/index.js';

	const id = $derived(page.params.id);

	let detail = $state<unknown>(null);
	let active = $state<unknown>(null);
	let error = $state<string | null>(null);
	let detailLoading = $state(true);
	let activeLoading = $state(true);

	$effect(() => {
		const expoId = id;
		if (!expoId) return;
		let cancelled = false;
		detailLoading = true;
		activeLoading = true;
		(async () => {
			error = null;
			try {
				const d = await apiClient().getExposition(expoId);
				if (!cancelled) {
					detail = d;
					detailLoading = false;
				}
				try {
					const a = await apiClient().getActiveExposition(expoId);
					if (!cancelled) {
						active = a;
						activeLoading = false;
					}
				} catch (e) {
					if (e instanceof ApiError && e.status === 404) {
						if (!cancelled) {
							active = null;
							activeLoading = false;
						}
					} else if (!cancelled) {
						error = e instanceof ApiError ? e.message : String(e);
						activeLoading = false;
					}
				}
			} catch (e) {
				if (!cancelled) {
					error = e instanceof ApiError ? e.message : String(e);
					detailLoading = false;
					activeLoading = false;
				}
			}
		})();
		return () => {
			cancelled = true;
		};
	});

	async function onDelete() {
		if (!id || !confirm('Delete this exposition?')) return;
		try {
			await apiClient().deleteExposition(id);
			goto('/expositions');
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<p class="mb-4">
	<a href="/expositions" class="text-primary text-sm hover:underline">← Expositions</a>
</p>

<div class="mb-6 flex flex-wrap items-center justify-between gap-4">
	<h2 class="text-xl font-semibold tracking-tight">Exposition {id}</h2>
	<Button variant="destructive" onclick={() => void onDelete()}>Delete</Button>
</div>

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<h3 class="mb-2 text-lg font-medium">Details</h3>
<JsonBlock value={detail} loading={detailLoading} />

<h3 class="mb-2 mt-6 text-lg font-medium">Active (endpoints)</h3>
{#if active}
	<JsonBlock value={active} />
{:else if !activeLoading}
	<p class="text-muted-foreground text-sm">No active exposition (404) or not ready yet.</p>
{:else}
	<JsonBlock value={null} loading={true} />
{/if}
