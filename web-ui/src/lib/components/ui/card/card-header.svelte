<script lang="ts">
  import { cn } from '#lib/utils.js';
  import type { HTMLAttributes } from 'svelte/elements';

  let {
    class: className,
    children,
    ...restProps
  }: HTMLAttributes<HTMLDivElement> & { children?: import('svelte').Snippet } = $props();
</script>

<div class={cn('flex flex-col space-y-1.5 p-6', className)} {...restProps}>
  {@render children?.()}
</div>

