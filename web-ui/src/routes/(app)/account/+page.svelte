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
  import PageHeader from '$lib/components/PageHeader.svelte';
  import { auth } from '$lib/stores/auth.svelte.js';
  import { getBootstrapConfig } from '$lib/api/config.js';
  import * as Alert from '$lib/components/ui/alert/index.js';
  import * as Card from '$lib/components/ui/card/index.js';
  import { onMount } from 'svelte';

  let version = $state('');
  let mode = $state('');

  onMount(async () => {
    try {
      const config = await getBootstrapConfig();
      version = config.version;
      mode = config.mode;
    } catch {
      // Non-critical.
    }
  });

  function formatTokenExpiry(iso: string | null | undefined): string {
    if (!iso) return '—';
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return '—';
    }
  }
</script>

<svelte:head>
  <title>Account — reShapr</title>
</svelte:head>

<div>
  <PageHeader title="Account" />

  <p class="text-muted-foreground mb-6 text-sm">
    Profile information is read from your server session. The control plane validates access on each API call.
  </p>

  {#if !auth.user}
    <Alert.Root variant="destructive">
      <Alert.Title>Unable to read session</Alert.Title>
      <Alert.Description class="text-sm">
        No active session. Sign out and sign in again.
      </Alert.Description>
    </Alert.Root>
  {:else}
    {#if auth.user.expired}
      <Alert.Root class="mb-6" variant="destructive">
        <Alert.Title>Session expired</Alert.Title>
        <Alert.Description class="text-sm">
          Your session has expired. Sign out and sign in again to continue.
        </Alert.Description>
      </Alert.Root>
    {/if}

    <div class="grid gap-4 sm:grid-cols-2">
      <Card.Root>
        <Card.Header>
          <Card.Title class="text-base">Identity</Card.Title>
        </Card.Header>
        <Card.Content class="space-y-3 text-sm">
          <div>
            <p class="text-muted-foreground text-xs">Username</p>
            <p class="font-medium">{auth.user.username ?? '—'}</p>
          </div>
          <div>
            <p class="text-muted-foreground text-xs">Email</p>
            <p class="font-medium">{auth.user.email ?? '—'}</p>
          </div>
          <div>
            <p class="text-muted-foreground text-xs">Groups</p>
            <p class="font-medium">
              {auth.user.groups && auth.user.groups.length > 0 ? auth.user.groups.join(', ') : '—'}
            </p>
          </div>
          <div>
            <p class="text-muted-foreground text-xs">Platform admin (UI)</p>
            <p class="font-medium">{auth.isAdmin ? 'Yes' : 'No'}</p>
          </div>
        </Card.Content>
      </Card.Root>

      <Card.Root>
        <Card.Header>
          <Card.Title class="text-base">Tenant & session</Card.Title>
        </Card.Header>
        <Card.Content class="space-y-3 text-sm">
          <div>
            <p class="text-muted-foreground text-xs">Organization</p>
            <p class="font-medium">
              {#if auth.user.org}
                <code class="text-xs">{auth.user.org}</code>
              {:else}
                —
              {/if}
            </p>
          </div>
          <div>
            <p class="text-muted-foreground text-xs">Roles</p>
            <p class="font-medium">
              {auth.user.roles && auth.user.roles.length > 0 ? auth.user.roles.join(', ') : '—'}
            </p>
          </div>
          <div>
            <p class="text-muted-foreground text-xs">Session expires</p>
            <p class="font-medium">{formatTokenExpiry(auth.user.expiresAt)}</p>
          </div>
          <div>
            <p class="text-muted-foreground text-xs">Mode / version</p>
            <p class="font-medium">
              {mode || '…'}
              {#if version}
                <span class="text-muted-foreground"> · {version}</span>
              {/if}
            </p>
          </div>
        </Card.Content>
      </Card.Root>
    </div>
  {/if}
</div>
