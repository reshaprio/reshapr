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
