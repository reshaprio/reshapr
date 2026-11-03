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
  import { gravatarUrl } from '#lib/gravatar.js';

  interface Props {
    /** The user's email address used to resolve a Gravatar avatar. */
    email?: string | null;
    /** Initials shown when no Gravatar avatar is available. */
    initials: string;
    /** Rendered avatar size in pixels (default 32). */
    size?: number;
    /** Extra classes applied to the avatar container. */
    class?: string;
  }

  let { email = null, initials, size = 32, class: className }: Props = $props();

  let src = $state<string | null>(null);
  let failed = $state(false);

  // Resolve the Gravatar URL whenever the email changes. Request a
  // higher-resolution image (2x) for crisp rendering on retina displays.
  $effect(() => {
    const currentEmail = email;
    src = null;
    failed = false;
    if (!currentEmail) return;
    gravatarUrl(currentEmail, size * 2).then((url) => {
      // Guard against a race when the email changed while hashing.
      if (currentEmail === email) {
        src = url;
      }
    });
  });

  const showGravatar = $derived(src !== null && !failed);
</script>

<span
  class={cn(
    'flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-primary text-xs font-semibold text-primary-foreground',
    className
  )}
  style={`width:${size}px;height:${size}px;`}
>
  {#if showGravatar}
    <img
      src={src}
      alt={initials}
      class="h-full w-full object-cover"
      onerror={() => (failed = true)}
    />
  {:else}
    {initials}
  {/if}
</span>

