<script lang="ts">
  import { cn } from '#lib/utils.js';
  import type { HTMLAttributes } from 'svelte/elements';

  let {
    class: className,
    value = 0,
    max = 100,
    ...restProps
  }: HTMLAttributes<HTMLDivElement> & { value?: number; max?: number } = $props();

  const percentage = $derived(Math.min(100, Math.max(0, (value / max) * 100)));
</script>

<div
  class={cn('relative h-4 w-full overflow-hidden rounded-full bg-secondary', className)}
  role="progressbar"
  aria-valuenow={value}
  aria-valuemin={0}
  aria-valuemax={max}
  {...restProps}
>
  <div
    class="h-full w-full flex-1 bg-primary transition-all"
    style="transform: translateX(-{100 - percentage}%)"
  ></div>
</div>

