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
	import { cn } from '#lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { GaugeIcon } from '@hugeicons/core-free-icons';

	export type QuotaInfo = { used: number; limit: number; remaining: number } | null;

	interface Props {
		/** Quota usage info. When `null`, nothing is rendered. */
		quota: QuotaInfo;
		/** Label describing the quota (e.g. "Gateway group quota"). */
		label: string;
		/** Message shown when the quota is reached. */
		fullMessage?: string;
		/** Extra classes for the outer container. */
		class?: string;
	}

	let {
		quota,
		label,
		fullMessage = 'Quota reached. Delete an existing item to create a new one.',
		class: className
	}: Props = $props();

	// Percentage used and severity-based highlight styling.
	const quotaPercent = $derived(
		quota && quota.limit > 0 ? Math.min(100, Math.round((quota.used / quota.limit) * 100)) : 0
	);
	const quotaSeverity = $derived<'ok' | 'warn' | 'full'>(
		quota === null
			? 'ok'
			: quota.remaining <= 0
				? 'full'
				: quota.remaining <= 1 || quotaPercent >= 80
					? 'warn'
					: 'ok'
	);
	const quotaStyles = {
		ok: { box: 'border-primary/30 bg-primary/5 text-primary', bar: 'bg-primary' },
		warn: {
			box: 'border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400',
			bar: 'bg-amber-500'
		},
		full: {
			box: 'border-destructive/40 bg-destructive/10 text-destructive',
			bar: 'bg-destructive'
		}
	} as const;
</script>

{#if quota}
	<div
		class={cn(
			'flex flex-col gap-3 rounded-xl border p-4 sm:flex-row sm:items-center sm:justify-between',
			quotaStyles[quotaSeverity].box,
			className
		)}
	>
		<div class="flex items-center gap-3">
			<span class="flex size-10 shrink-0 items-center justify-center rounded-lg bg-current/10">
				<HugeiconsIcon icon={GaugeIcon} class="size-5" />
			</span>
			<div>
				<div class="text-sm font-semibold">
					{label} — {quota.used} / {quota.limit} used
				</div>
				<div class="text-xs opacity-80">
					{#if quota.remaining <= 0}
						{fullMessage}
					{:else}
						{quota.remaining} remaining
					{/if}
				</div>
			</div>
		</div>
		<div class="flex items-center gap-3 sm:w-64">
			<div class="h-2 flex-1 overflow-hidden rounded-full bg-current/15">
				<div
					class={cn('h-full rounded-full transition-all', quotaStyles[quotaSeverity].bar)}
					style="width: {quotaPercent}%"
				></div>
			</div>
			<span class="font-mono text-sm font-semibold tabular-nums">{quotaPercent}%</span>
		</div>
	</div>
{/if}

