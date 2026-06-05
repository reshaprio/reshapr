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
	import JsonBlock from '$lib/components/JsonBlock.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';

	let data = $state<unknown>(null);
	let error = $state<string | null>(null);
	let loading = $state(true);

	$effect(() => {
		(async () => {
			try {
				error = null;
				data = await apiClient().getQuotas();
			} catch (e) {
				error = e instanceof ApiError ? e.message : String(e);
			} finally {
				loading = false;
			}
		})();
	});
</script>

<PageHeader title="Quotas" />

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<JsonBlock value={data} {loading} />
