<script lang="ts">
  import { cn } from '#lib/utils.js';
  import type { HTMLAttributes } from 'svelte/elements';

  let {
    class: className,
    variant = 'default',
    children,
    ...restProps
  }: HTMLAttributes<HTMLDivElement> & {
    variant?: 'default' | 'secondary' | 'destructive' | 'outline';
    children?: import('svelte').Snippet;
  } = $props();

  const variantClasses: Record<string, string> = {
    default: 'border-transparent bg-primary text-primary-foreground',
    secondary: 'border-transparent bg-secondary text-secondary-foreground',
    destructive: 'border-transparent bg-destructive text-destructive-foreground',
    outline: 'text-foreground'
  };
</script>

<div
  class={cn(
    'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors',
    variantClasses[variant],
    className
  )}
  {...restProps}
>
  {@render children?.()}
</div>

