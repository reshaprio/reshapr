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
	import { Input } from '#lib/components/ui/input/index.js';
	import type { HTMLInputAttributes } from 'svelte/elements';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { ViewIcon, ViewOffSlashIcon } from '@hugeicons/core-free-icons';
	import { cn } from '#lib/utils.js';

	let {
		value = $bindable(''),
		id,
		placeholder,
		autocomplete = 'new-password',
		class: className
	}: {
		value?: string;
		id?: string;
		placeholder?: string;
		autocomplete?: HTMLInputAttributes['autocomplete'];
		class?: string;
	} = $props();

	let visible = $state(false);
</script>

<div class="relative">
	<Input
		{id}
		{placeholder}
		{autocomplete}
		type={visible ? 'text' : 'password'}
		bind:value
		class={cn('pr-9', className)}
	/>
	<button
		type="button"
		tabindex="-1"
		onclick={() => (visible = !visible)}
		class="text-muted-foreground hover:text-foreground absolute top-1/2 right-2 -translate-y-1/2 transition-colors"
		aria-label={visible ? 'Hide value' : 'Show value'}
		title={visible ? 'Hide' : 'Show'}
	>
		<HugeiconsIcon icon={visible ? ViewOffSlashIcon : ViewIcon} size={16} />
	</button>
</div>

