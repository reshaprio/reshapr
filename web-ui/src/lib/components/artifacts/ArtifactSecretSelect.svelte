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
	import { apiClient } from '#lib/api/client.js';
	import * as Select from '#lib/components/ui/select/index.js';

	/**
	 * Reusable picker listing secrets of type `ARTIFACT`. The selected value is the
	 * secret name (empty string means "no secret"). Designed to be embedded in the
	 * import/attach dialog and, later, in the Quick start wizard.
	 */
	let {
		value = $bindable(''),
		id,
		disabled = false
	}: {
		value?: string;
		id?: string;
		disabled?: boolean;
	} = $props();

	type SecretRef = { name?: string; type?: string };

	// Sentinel value used by bits-ui to represent the "None" choice (empty string
	// values are not reliably handled by the underlying Select primitive).
	const NONE = '__none__';

	let secrets = $state<string[]>([]);
	let loading = $state(false);
	let loadError = $state<string | null>(null);

	async function load() {
		loading = true;
		loadError = null;
		try {
			const data = await apiClient().listSecretRefs();
			const list = Array.isArray(data) ? (data as SecretRef[]) : [];
			secrets = list
				.filter((s) => (s?.type ?? '').toUpperCase() === 'ARTIFACT')
				.map((s) => s.name)
				.filter((n): n is string => typeof n === 'string' && n.length > 0);
		} catch (e) {
			loadError = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void load();
	});

	const selectValue = $derived(value === '' ? NONE : value);
	const triggerLabel = $derived(value === '' ? 'No secret' : value);

	function onValueChange(v: string) {
		value = v === NONE ? '' : v;
	}
</script>

<Select.Root type="single" value={selectValue} {onValueChange} {disabled}>
	<Select.Trigger {id} class="w-full">{triggerLabel}</Select.Trigger>
	<Select.Content>
		<Select.Item value={NONE}>No secret</Select.Item>
		{#each secrets as name (name)}
			<Select.Item value={name}>{name}</Select.Item>
		{/each}
	</Select.Content>
</Select.Root>
{#if loading}
	<p class="text-muted-foreground text-xs">Loading secrets…</p>
{:else if loadError}
	<p class="text-destructive text-xs">{loadError}</p>
{:else if secrets.length === 0}
	<p class="text-muted-foreground text-xs">
		No <code class="text-xs">ARTIFACT</code> secret defined yet.
	</p>
{/if}

