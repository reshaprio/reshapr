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
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { auth } from '#lib/stores/auth.svelte.js';
  import { getBootstrapConfig } from '#lib/api/config.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import { Input } from '#lib/components/ui/input/index.js';
  import { Label } from '#lib/components/ui/label/index.js';
  import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '#lib/components/ui/card/index.js';

  let username = $state('');
  let password = $state('');
  let error = $state('');
  let sessionExpired = $state(false);
  let loading = $state(false);
  let configLoading = $state(true);
  let oidcEnabled = $state(false);

  onMount(async () => {
    // Check for error from OIDC callback or a lost/expired session.
    const urlError = page.url.searchParams.get('error');
    if (urlError === 'session_expired') {
      sessionExpired = true;
    } else if (urlError === 'missing_token') {
      error = 'Authentication failed: no token received.';
    } else if (urlError === 'invalid_token') {
      error = 'Authentication failed: invalid token received.';
    } else if (urlError === 'access_denied') {
      error =
        'Your account is not allowed to access reShapr. Please contact your administrator to request access.';
    }

    // If already authenticated, redirect to app.
    const hasSession = await auth.initSession();
    if (hasSession) {
      goto('/');
      return;
    }

    // Fetch bootstrap config to determine auth mode.
    try {
      const config = await getBootstrapConfig();
      oidcEnabled = config.oidcEnabled;
      console.log('OIDC enabled:', oidcEnabled);
      auth.setAuthMode(oidcEnabled ? 'oidc' : 'reshapr');
    } catch {
      error = 'Unable to connect to the server.';
    } finally {
      configLoading = false;
    }
  });

  async function handleLogin(e: Event) {
    e.preventDefault();
    error = '';
    loading = true;

    const result = await auth.login(username, password);
    loading = false;

    if (result.ok) {
      goto('/');
    } else {
      error = result.error ?? 'Login failed';
    }
  }

  function handleOidcLogin() {
    // Redirect to SvelteKit server route which will redirect to the control plane OIDC.
    window.location.href = '/api/auth/login/oidc';
  }
</script>

<svelte:head>
  <title>Login — reShapr</title>
</svelte:head>

<div class="flex min-h-screen items-center justify-center bg-background px-4">
  <Card class="w-full max-w-sm">
    <CardHeader class="text-center">
      <CardTitle class="text-2xl font-bold flex items-center justify-center gap-2">
        <img src="/reShapr-icon.png" alt="" class="h-8" />
        reShapr
      </CardTitle>
      <CardDescription>Sign in to your account</CardDescription>
    </CardHeader>

    <CardContent>
      {#if sessionExpired}
        <!-- Session-expired notice, styled like the SSO auth error box. -->
        <div
          role="alert"
          class="mb-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
        >
          <p class="font-medium">Session expired</p>
          <p class="mt-1 text-destructive/90">
            Your previous session has expired. Please sign in again.
          </p>
        </div>
      {/if}

      {#if configLoading}
        <div class="flex justify-center py-8">
          <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
        </div>
      {:else if oidcEnabled}
        <!-- OIDC / SSO login -->
        <div class="space-y-4">
          {#if error}
            <div
              role="alert"
              class="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            >
              <p class="font-medium">Access denied</p>
              <p class="mt-1 text-destructive/90">{error}</p>
            </div>
          {/if}
          <Button class="w-full" onclick={handleOidcLogin}>
            Login with SSO
          </Button>
        </div>
      {:else}
        <!-- Username / Password login -->
        <form onsubmit={handleLogin} class="space-y-4">
          <div class="space-y-2">
            <Label for="username">Username</Label>
            <Input
              id="username"
              type="text"
              placeholder="Enter your username"
              bind:value={username}
              required
              autocomplete="username"
            />
          </div>

          <div class="space-y-2">
            <Label for="password">Password</Label>
            <Input
              id="password"
              type="password"
              placeholder="Enter your password"
              bind:value={password}
              required
              autocomplete="current-password"
            />
          </div>

          {#if error}
            <p class="text-sm text-destructive text-center">{error}</p>
          {/if}

          <Button type="submit" class="w-full" disabled={loading || !username || !password}>
            {#if loading}
              <div class="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent"></div>
              Signing in…
            {:else}
              Sign in
            {/if}
          </Button>
        </form>
      {/if}
    </CardContent>
  </Card>
</div>




