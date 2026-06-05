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
	import { cn } from '$lib/utils.js';

	let {
		text,
		class: className = '',
		maxHeight = '5.5rem'
	}: {
		text: string;
		class?: string;
		/** CSS length, e.g. `5.5rem` or `24rem` */
		maxHeight?: string;
	} = $props();
</script>

<div
	class={cn(
		'bg-muted/40 overflow-auto rounded-md border px-2 py-1.5',
		className
	)}
	style:max-height={maxHeight}
	title={text}
>
	<code class="text-foreground block font-mono text-xs leading-relaxed break-all whitespace-pre-wrap"
		>{text}</code>
</div>
