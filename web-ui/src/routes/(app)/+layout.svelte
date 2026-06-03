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
  import { auth } from '$lib/stores/auth.svelte.js';
  import { sidebar } from '$lib/stores/sidebar.svelte.js';
  import { getBootstrapConfig } from '$lib/api/config.js';
  import { cn } from '$lib/utils.js';
  import * as Collapsible from '$lib/components/ui/collapsible/index.js';
  import { HugeiconsIcon } from '@hugeicons/svelte';
  import {
    DashboardSquare01Icon,
    ApiIcon,
    Logout01Icon,
    SidebarLeft01Icon,
    SidebarRight01Icon,
    ChevronDownIcon,
    Building01Icon,
    UserIcon,
    FlaskConicalIcon
  } from '@hugeicons/core-free-icons';

  let { children } = $props();

  let version = $state('');
  let userMenuOpen = $state(false);
  let orgSelectorOpen = $state(false);
  let experimentalOpen = $state(false);

  const experimentalNav = [
    { href: '/artifacts', label: 'Artifacts' },
    { href: '/plans', label: 'Plans' },
    { href: '/expositions', label: 'Expositions' },
    { href: '/mcp-custom-tools', label: 'MCP custom tools' },
    { href: '/mcp-prompts', label: 'MCP prompts' },
    { href: '/secrets', label: 'Secrets' },
    { href: '/gateway-groups', label: 'Gateway groups' },
    { href: '/quotas', label: 'Quotas' },
    { href: '/api-tokens', label: 'API tokens' },
  ] as const;

  onMount(async () => {
    const hasSession = await auth.initSession();
    if (!hasSession) {
      goto('/login');
      return;
    }

    try {
      const config = await getBootstrapConfig();
      version = config.version;
    } catch {
      // Non-critical.
    }
  });

  interface NavItem {
    href: string;
    label: string;
    icon: any;
    adminOnly?: boolean;
  }

  interface NavSection {
    title?: string;
    adminOnly?: boolean;
    items: NavItem[];
  }

  const navigation: NavSection[] = [
    {
      items: [
        { href: '/', label: 'Dashboard', icon: DashboardSquare01Icon },
        { href: '/services', label: 'Services', icon: ApiIcon },
        { href: '/account', label: 'Account', icon: UserIcon },
      ]
    },
    {
      title: 'Admin',
      adminOnly: true,
      items: [
        { href: '/admin/organizations', label: 'Organizations', icon: Building01Icon },
      ]
    }
  ];

  function isActive(href: string): boolean {
    const path = page.url.pathname;
    if (href === '/') return path === '/';
    if (href === '/services') {
      return path === '/services' || path.startsWith('/services/');
    }
    if (href === '/plans') {
      return path === '/plans' || path.startsWith('/plans/');
    }
    return path === href || path.startsWith(href + '/');
  }

  function isExperimentalActive(): boolean {
    return experimentalNav.some((item) => isActive(item.href));
  }

  $effect(() => {
    if (isExperimentalActive()) {
      experimentalOpen = true;
    }
  });

  function experimentalNavClass(href: string): string {
    return cn(
      'block rounded-md px-2 py-1.5 text-sm transition-colors',
      isActive(href)
        ? 'bg-sidebar-accent font-medium text-sidebar-accent-foreground'
        : 'text-sidebar-foreground/80 hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground'
    );
  }

  function handleSignOut() {
    userMenuOpen = false;
    auth.logout().then(() => goto('/login'));
  }

  async function selectOrganization(orgName: string) {
    orgSelectorOpen = false;
    if (orgName === auth.currentOrg) return;

    const result = await auth.switchOrganization(orgName);
    if (result.ok) {
      // Reload the current page to refresh data with the new organization context.
      window.location.reload();
    }
  }
</script>

<svelte:document onclick={() => { userMenuOpen = false; orgSelectorOpen = false; }} />

{#if auth.loading}
  <div class="flex min-h-screen items-center justify-center">
    <div class="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
  </div>
{:else if auth.isAuthenticated}
  <div class="flex h-screen overflow-hidden">
    <!-- Sidebar -->
    <aside
      class="flex shrink-0 flex-col border-r border-border bg-sidebar transition-all duration-200
        {sidebar.collapsed ? 'w-14' : 'w-60'}"
    >
      <!-- Logo + collapse toggle -->
      <div class="flex h-14 shrink-0 items-center border-b border-sidebar-border px-3 {sidebar.collapsed ? 'justify-center' : 'justify-between'}">
        {#if !sidebar.collapsed}
          <a href="/" class="flex items-center">
            <img src="/reShapr-icon.png" alt="reShapr" class="h-7" />
            <span class="ml-2 text-lg font-semibold tracking-tight text-sidebar-foreground">reShapr</span>
          </a>
        {:else}
          <a href="/" class="flex items-center justify-center">
            <img src="/reShapr-icon.png" alt="reShapr" class="h-7" />
          </a>
        {/if}
        {#if !sidebar.collapsed}
          <button
            onclick={() => sidebar.toggle()}
            class="inline-flex h-7 w-7 items-center justify-center rounded-md text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
            aria-label="Collapse sidebar"
          >
            <HugeiconsIcon icon={SidebarLeft01Icon} size={16} />
          </button>
        {/if}
      </div>

      {#if sidebar.collapsed}
        <!-- Expand button when collapsed -->
        <div class="flex justify-center py-2">
          <button
            onclick={() => sidebar.toggle()}
            class="inline-flex h-7 w-7 items-center justify-center rounded-md text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
            aria-label="Expand sidebar"
          >
            <HugeiconsIcon icon={SidebarRight01Icon} size={16} />
          </button>
        </div>
      {/if}

      <!-- Organization selector -->
      {#if !sidebar.collapsed}
        <div class="relative px-3 py-2">
          <button
            onclick={(e) => { e.stopPropagation(); orgSelectorOpen = !orgSelectorOpen; }}
            class="flex w-full items-center gap-2 rounded-md border border-sidebar-border px-2 py-1.5 text-sm text-sidebar-foreground hover:bg-sidebar-accent/50 transition-colors"
          >
            <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-primary/10 text-primary">
              <HugeiconsIcon icon={Building01Icon} size={14} />
            </span>
            <span class="flex-1 truncate text-left font-medium">{auth.currentOrg}</span>
            {#if auth.hasMultipleOrgs}
              <HugeiconsIcon icon={ChevronDownIcon} size={14} />
            {/if}
          </button>

          <!-- Org dropdown -->
          {#if orgSelectorOpen && auth.hasMultipleOrgs}
            <div
              class="absolute left-3 right-3 top-full z-50 mt-1 rounded-md border border-border bg-popover py-1 shadow-md"
              role="menu"
              tabindex="-1"
              onclick={(e) => e.stopPropagation()}
              onkeydown={(e) => { if (e.key === 'Escape') orgSelectorOpen = false; }}
            >
              {#each auth.organizations as org}
                <button
                  onclick={() => selectOrganization(org.name)}
                  class="flex w-full items-center gap-2 px-2 py-1.5 text-sm transition-colors
                    {org.name === auth.currentOrg
                      ? 'bg-accent text-accent-foreground font-medium'
                      : 'text-popover-foreground hover:bg-accent/50'}"
                >
                  <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-primary/10 text-primary">
                    <HugeiconsIcon icon={Building01Icon} size={14} />
                  </span>
                  <span class="truncate">{org.name}</span>
                </button>
              {/each}
            </div>
          {/if}
        </div>
      {:else}
        <!-- Collapsed: just show org icon -->
        <div class="flex justify-center py-2" title={auth.currentOrg}>
          <span class="flex h-8 w-8 items-center justify-center rounded-md text-sidebar-foreground/60">
            <HugeiconsIcon icon={Building01Icon} size={16} />
          </span>
        </div>
      {/if}

      <!-- Navigation -->
      <nav class="flex flex-1 flex-col gap-1 overflow-y-auto px-2 py-2">
        {#each navigation as section}
          {#if !section.adminOnly || auth.isAdmin}
            {#if section.title}
              {#if !sidebar.collapsed}
                <div class="mt-4 mb-1 px-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {section.title}
                </div>
              {:else}
                <div class="mt-4 mb-1 border-t border-sidebar-border"></div>
              {/if}
            {/if}

            {#each section.items as item}
              {#if !item.adminOnly || auth.isAdmin}
                <a
                  href={item.href}
                  class="flex items-center gap-3 rounded-md px-2 py-2 text-sm font-medium transition-colors
                    {isActive(item.href)
                      ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                      : 'text-sidebar-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground'}
                    {sidebar.collapsed ? 'justify-center' : ''}"
                  title={sidebar.collapsed ? item.label : undefined}
                >
                  <span class="flex h-5 w-5 shrink-0 items-center justify-center">
                    <HugeiconsIcon icon={item.icon} size={18} />
                  </span>
                  {#if !sidebar.collapsed}
                    <span>{item.label}</span>
                  {/if}
                </a>
              {/if}
            {/each}
          {/if}
        {/each}

        {#if !sidebar.collapsed}
          <Collapsible.Root bind:open={experimentalOpen} class="mt-4">
            <Collapsible.Trigger
              class={cn(
                'flex w-full items-center gap-3 rounded-md px-2 py-2 text-sm font-medium transition-colors',
                isExperimentalActive()
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                  : 'text-sidebar-foreground hover:bg-sidebar-accent/50'
              )}
            >
              <span class="flex h-5 w-5 shrink-0 items-center justify-center">
                <HugeiconsIcon icon={FlaskConicalIcon} size={18} />
              </span>
              <span class="flex-1 text-left">Experimental</span>
              <span class={cn('inline-flex shrink-0 transition-transform duration-200', experimentalOpen && 'rotate-180')}>
                <HugeiconsIcon icon={ChevronDownIcon} size={14} />
              </span>
            </Collapsible.Trigger>
            <Collapsible.Content class="mt-1 space-y-0.5 pl-2">
              {#each experimentalNav as item (item.href)}
                <a href={item.href} class={experimentalNavClass(item.href)}>{item.label}</a>
              {/each}
            </Collapsible.Content>
          </Collapsible.Root>
        {/if}
      </nav>

      <!-- User profile at bottom -->
      <div class="relative shrink-0 border-t border-sidebar-border p-2">
        <button
          onclick={(e) => { e.stopPropagation(); userMenuOpen = !userMenuOpen; }}
          class="flex w-full items-center gap-2 rounded-md px-2 py-2 text-sm transition-colors hover:bg-sidebar-accent/50
            {sidebar.collapsed ? 'justify-center' : ''}"
        >
          <!-- Avatar with initials -->
          <span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
            {auth.initials}
          </span>
          {#if !sidebar.collapsed}
            <div class="flex-1 text-left min-w-0">
              <div class="truncate font-medium text-sidebar-foreground">{auth.user?.username}</div>
              <div class="truncate text-xs text-muted-foreground">{auth.user?.email}</div>
            </div>
          {/if}
        </button>

        <!-- User dropdown menu (opens upward and to the right) -->
        {#if userMenuOpen}
          <div
            class="absolute z-50 rounded-md border border-border bg-popover py-1 shadow-md
              bottom-2 left-full ml-2"
            role="menu"
            tabindex="-1"
            onclick={(e) => e.stopPropagation()}
            onkeydown={(e) => { if (e.key === 'Escape') userMenuOpen = false; }}
          >
            {#if sidebar.collapsed}
              <!-- Show user info in dropdown when collapsed -->
              <div class="border-b border-border px-3 py-2">
                <div class="text-sm font-medium text-popover-foreground">{auth.user?.username}</div>
                <div class="text-xs text-muted-foreground">{auth.user?.email}</div>
              </div>
            {/if}
            <button
              onclick={handleSignOut}
              class="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-accent transition-colors whitespace-nowrap"
            >
              <HugeiconsIcon icon={Logout01Icon} size={16} />
              <span>Sign out</span>
            </button>
          </div>
        {/if}
      </div>
    </aside>

    <!-- Main content -->
    <main class="flex flex-1 flex-col overflow-y-auto">
      <div class="flex-1 p-6">
        {@render children()}
      </div>

      <!-- Footer -->
      <footer class="shrink-0 border-t border-border px-4 py-2 text-xs text-muted-foreground">
        <div class="flex items-center justify-between">
          <span>reShapr{version ? ` v${version}` : ''}</span>
          <span>© {new Date().getFullYear()} The Reshapr Authors</span>
        </div>
      </footer>
    </main>
  </div>
{/if}

