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

<!--
  Shared confirmation dialog.

  Replaces the native `confirm()` browser prompt with a styled, configurable
  dialog. It is generic enough to be reused for any confirmable action
  (deletion, revocation, …) and lets each caller provide:
    - a title and a plain confirmation `description`
    - custom "consequences" content through the default snippet (children),
      so every entity type can explain what will happen on confirmation
    - the confirm/cancel button labels and the confirm button variant

  The dialog owns the busy + error lifecycle: `onConfirm` may be async and may
  throw. While it runs, the buttons are disabled; on success the dialog closes;
  on failure the (formatted) error is shown inline and the dialog stays open.
-->

<script lang="ts">
	import type { Snippet } from 'svelte';
	import * as Dialog from '#lib/components/ui/dialog/index.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import { formatApiError } from '#lib/format-api-error.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { Delete02Icon } from '@hugeicons/core-free-icons';

	type ConfirmVariant = 'destructive' | 'default';

	let {
		open = $bindable(false),
		title = 'Confirm action',
		description = undefined,
		confirmLabel = 'Confirm',
		confirmingLabel = undefined,
		cancelLabel = 'Cancel',
		variant = 'destructive',
		icon = Delete02Icon,
		onConfirm,
		onCancel = undefined,
		children = undefined
	}: {
		/** Controls the visibility of the dialog. Use `bind:open`. */
		open?: boolean;
		/** Dialog heading. */
		title?: string;
		/** Main confirmation message shown below the title. */
		description?: string;
		/** Label of the confirm button (e.g. "Delete", "Revoke"). */
		confirmLabel?: string;
		/** Label of the confirm button while the action runs. Defaults to `${confirmLabel}…`. */
		confirmingLabel?: string;
		/** Label of the cancel button. */
		cancelLabel?: string;
		/** Confirm button variant. */
		variant?: ConfirmVariant;
		/** Leading icon in the title. Pass `null` to hide it. */
		icon?: typeof Delete02Icon | null;
		/** Called when the user confirms. May be async and may throw. */
		onConfirm: () => void | Promise<void>;
		/** Optional callback when the dialog is dismissed without confirming. */
		onCancel?: () => void;
		/** Custom "consequences" content rendered between the description and the actions. */
		children?: Snippet;
	} = $props();

	let busy = $state(false);
	let error = $state<string | null>(null);

	async function confirm() {
		busy = true;
		error = null;
		try {
			await onConfirm();
			open = false;
		} catch (e) {
			error = formatApiError(e);
		} finally {
			busy = false;
		}
	}

	function cancel() {
		if (busy) return;
		error = null;
		open = false;
		onCancel?.();
	}

	function handleOpenChange(next: boolean) {
		if (!next) cancel();
	}
</script>

<Dialog.Root {open} onOpenChange={handleOpenChange}>
	<Dialog.Content class="sm:max-w-lg">
		<Dialog.Header>
			<Dialog.Title class="flex items-center gap-2">
				{#if icon}
					<HugeiconsIcon
						{icon}
						size={20}
						class={variant === 'destructive' ? 'text-destructive' : 'text-muted-foreground'}
					/>
				{/if}
				{title}
			</Dialog.Title>
			{#if description}
				<Dialog.Description>{description}</Dialog.Description>
			{/if}
		</Dialog.Header>

		{#if error || children}
			<div class="space-y-4">
				{#if error}
					<ApiErrorAlert message={error} />
				{/if}
				{#if children}
					{@render children()}
				{/if}
			</div>
		{/if}

		<Dialog.Footer>
			<Button variant="outline" onclick={cancel} disabled={busy}>{cancelLabel}</Button>
			<Button {variant} onclick={() => void confirm()} disabled={busy}>
				{busy ? (confirmingLabel ?? `${confirmLabel}…`) : confirmLabel}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

