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
	import { Button } from '#lib/components/ui/button/index.js';
	import * as Dialog from '#lib/components/ui/dialog/index.js';
	import ImportArtifactForm, { type ImportArtifactMode } from './ImportArtifactForm.svelte';

	/**
	 * Standalone dialog wrapping {@link ImportArtifactForm} to import a service
	 * specification (main artifact) or attach a pre-existing artifact. Controlled
	 * through the bindable `open` prop; `onDone` is called with the API response
	 * on success. The embeddable form itself is reused by the Quick start wizard.
	 */
	let {
		open = $bindable(false),
		mode,
		title,
		description,
		onDone
	}: {
		open?: boolean;
		mode: ImportArtifactMode;
		title?: string;
		description?: string;
		onDone?: (result: unknown) => void;
	} = $props();

	const DEFAULTS = {
		import: {
			title: 'Import a service specification',
			description:
				'Upload a specification file or reference a URL to register a new service and its main artifact.',
			submitLabel: 'Import'
		},
		attach: {
			title: 'Attach an existing artifact',
			description:
				'Upload an artifact document or reference a URL to attach it to an existing service.',
			submitLabel: 'Attach'
		}
	} as const;

	const resolvedTitle = $derived(title ?? DEFAULTS[mode].title);
	const resolvedDescription = $derived(description ?? DEFAULTS[mode].description);
	const submitLabel = $derived(DEFAULTS[mode].submitLabel);

	let form = $state<ReturnType<typeof ImportArtifactForm> | null>(null);
	let submitting = $state(false);

	function handleOpenChange(next: boolean) {
		if (submitting) return;
		open = next;
		if (!next) form?.reset();
	}

	async function handleSubmit() {
		const result = await form?.submit();
		if (result != null) open = false;
	}
</script>

<Dialog.Root {open} onOpenChange={handleOpenChange}>
	<Dialog.Content class="sm:max-w-lg">
		<Dialog.Header>
			<Dialog.Title>{resolvedTitle}</Dialog.Title>
			<Dialog.Description>{resolvedDescription}</Dialog.Description>
		</Dialog.Header>

		<ImportArtifactForm bind:this={form} {mode} {onDone} bind:submitting />

		<Dialog.Footer>
			<Button variant="outline" onclick={() => handleOpenChange(false)} disabled={submitting}>
				Cancel
			</Button>
			<Button onclick={() => void handleSubmit()} disabled={submitting}>
				{submitting ? 'Working…' : submitLabel}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

