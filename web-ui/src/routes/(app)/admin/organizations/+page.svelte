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
  import { auth } from '$lib/stores/auth.svelte.js';
  import { Button } from '$lib/components/ui/button/index.js';
  import { Input } from '$lib/components/ui/input/index.js';
  import { Label } from '$lib/components/ui/label/index.js';
  import { Tabs, TabsContent, TabsList, TabsTrigger } from '$lib/components/ui/tabs/index.js';
  import {
    Table, TableBody, TableCell, TableHead, TableHeader, TableRow
  } from '$lib/components/ui/table/index.js';
  import {
    Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose
  } from '$lib/components/ui/sheet/index.js';
  import {
    DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger
  } from '$lib/components/ui/dropdown-menu/index.js';
  import { HugeiconsIcon } from "@hugeicons/svelte";
  import {
    Building01Icon, MoreVerticalIcon, UserGroupIcon, UserIcon, Crown
  } from '@hugeicons/core-free-icons';
  import { Badge } from "$lib/components/ui/badge";
  import UsersTab from './UsersTab.svelte';

  // ── Types ──────────────────────────────────────────────────
  interface Organization {
    name: string;
    description: string | null;
    icon: string | null;
    ownerUsername: string | null;
  }

  interface User {
    username: string;
    email: string;
    firstname: string | null;
    lastname: string | null;
    defaultOrganizationName: string | null;
  }

  // ── Active tab ────────────────────────────────────────────
  let activeTab = $state('organizations');

  // ── Pagination constants ──────────────────────────────────
  const PAGE_SIZE = 20;
  const MAX_PREFETCH_PAGES = 3;

  // ── Organizations state ───────────────────────────────────
  let allOrganizations = $state<Organization[]>([]);
  let orgPage = $state(0);
  let orgLoading = $state(true);
  let orgFetchError = $state('');

  // Derived: current page slice & pagination visibility
  const orgDisplayed = $derived(allOrganizations.slice(orgPage * PAGE_SIZE, (orgPage + 1) * PAGE_SIZE));
  const orgTotalPages = $derived(Math.ceil(allOrganizations.length / PAGE_SIZE));
  const orgShowPagination = $derived(allOrganizations.length > PAGE_SIZE);
  const orgHasNextPage = $derived(orgPage < orgTotalPages - 1);

  // ── Users list (populated by UsersTab, used for owner autocomplete) ──
  let allUsers = $state<User[]>([]);

  // ── Create Organization drawer state ──────────────────────
  let orgDrawerOpen = $state(false);
  let orgName = $state('');
  let orgDescription = $state('');
  let orgIcon = $state('');
  let orgSubmitting = $state(false);
  let orgFormError = $state('');
  let orgFormSuccess = $state('');

  // ── Assign Owner drawer state ─────────────────────────────
  let ownerDrawerOpen = $state(false);
  let ownerTargetOrg = $state<Organization | null>(null);
  let ownerUsername = $state('');
  let ownerSubmitting = $state(false);
  let ownerFormError = $state('');
  let ownerFormSuccess = $state('');
  let ownerSuggestionsOpen = $state(false);
  let ownerHighlightIndex = $state(-1);

  const ownerFilteredUsers = $derived(
    ownerUsername.trim() === ''
      ? allUsers
      : allUsers.filter(u => u.username.toLowerCase().includes(ownerUsername.toLowerCase()))
  );

  // ── UsersTab component ref ────────────────────────────────
  let usersTabRef: ReturnType<typeof UsersTab> | undefined = $state();

  // ── Lifecycle ─────────────────────────────────────────────
  onMount(() => {
    if (!auth.isAdmin) {
      goto('/');
      return;
    }
    fetchOrganizations();
  });

  // ── Generic page fetcher ──────────────────────────────────
  async function fetchPage<T>(endpoint: string, page: number): Promise<T[] | null> {
    const res = await fetch(`${endpoint}?page=${page}&size=${PAGE_SIZE}`);
    if (!res.ok) return null;
    return await res.json() as T[];
  }

  // ── Organizations data fetching ───────────────────────────
  async function fetchOrganizations() {
    orgLoading = true;
    orgFetchError = '';
    orgPage = 0;
    try {
      const first = await fetchPage<Organization>('/api/admin/organizations', 0);
      if (first === null) {
        orgFetchError = 'Failed to load organizations.';
        return;
      }
      allOrganizations = first;

      if (first.length === PAGE_SIZE) {
        prefetchPages<Organization>('/api/admin/organizations', 1, (items) => {
          allOrganizations = items;
        }, allOrganizations);
      }
    } catch {
      orgFetchError = 'Network error while loading organizations.';
    } finally {
      orgLoading = false;
    }
  }

  function orgPreviousPage() {
    if (orgPage > 0) orgPage--;
  }
  function orgNextPage() {
    if (orgHasNextPage) orgPage++;
  }

  // ── Async pre-fetching (up to MAX_PREFETCH_PAGES extra pages) ──
  async function prefetchPages<T>(
    endpoint: string,
    startPage: number,
    update: (items: T[]) => void,
    accumulated: T[]
  ) {
    let current = [...accumulated];
    for (let p = startPage; p < startPage + MAX_PREFETCH_PAGES; p++) {
      const page = await fetchPage<T>(endpoint, p);
      if (page === null || page.length === 0) break;
      current = [...current, ...page];
      update(current);
      if (page.length < PAGE_SIZE) break;
    }
  }

  // ── Create Organization form ──────────────────────────────
  function resetOrgForm() {
    orgName = ''; orgDescription = ''; orgIcon = '';
    orgFormError = ''; orgFormSuccess = '';
  }

  function openOrgDrawer() {
    resetOrgForm();
    orgDrawerOpen = true;
  }

  async function handleCreateOrganization(e: Event) {
    e.preventDefault();
    orgFormError = '';
    orgFormSuccess = '';
    orgSubmitting = true;

    try {
      const res = await fetch('/api/admin/organizations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: orgName,
          description: orgDescription || undefined,
          icon: orgIcon || undefined
        })
      });

      if (res.status === 201) {
        resetOrgForm();
        orgFormSuccess = 'Organization created successfully.';
        orgPage = 0;
        await fetchOrganizations();
        setTimeout(() => { orgDrawerOpen = false; }, 1200);
      } else if (res.status === 409) {
        orgFormError = 'An organization with this name already exists.';
      } else if (res.status === 404) {
        orgFormError = 'Owner user not found. Please check the username.';
      } else if (res.status === 403) {
        orgFormError = 'Forbidden: admin access required.';
      } else if (res.status === 401) {
        orgFormError = 'Session expired. Please refresh the page.';
      } else {
        const body = await res.text();
        orgFormError = `Failed to create organization: ${body || res.statusText}`;
      }
    } catch {
      orgFormError = 'Network error. Please try again.';
    } finally {
      orgSubmitting = false;
    }
  }

  // ── Assign Owner form ──────────────────────────────────────
  function resetOwnerForm() {
    ownerUsername = '';
    ownerFormError = ''; ownerFormSuccess = '';
    ownerSuggestionsOpen = false;
    ownerHighlightIndex = -1;
  }

  function openOwnerDrawer(org: Organization) {
    resetOwnerForm();
    ownerTargetOrg = org;
    ownerUsername = org.ownerUsername ?? '';
    ownerDrawerOpen = true;
  }

  function selectOwnerSuggestion(username: string) {
    ownerUsername = username;
    ownerSuggestionsOpen = false;
    ownerHighlightIndex = -1;
  }

  function handleOwnerInputKeydown(e: KeyboardEvent) {
    if (!ownerSuggestionsOpen || ownerFilteredUsers.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      ownerHighlightIndex = (ownerHighlightIndex + 1) % ownerFilteredUsers.length;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      ownerHighlightIndex = ownerHighlightIndex <= 0 ? ownerFilteredUsers.length - 1 : ownerHighlightIndex - 1;
    } else if (e.key === 'Enter' && ownerHighlightIndex >= 0) {
      e.preventDefault();
      selectOwnerSuggestion(ownerFilteredUsers[ownerHighlightIndex].username);
    } else if (e.key === 'Escape') {
      ownerSuggestionsOpen = false;
    }
  }

  async function handleAssignOwner(e: Event) {
    e.preventDefault();
    ownerFormError = '';
    ownerFormSuccess = '';
    ownerSubmitting = true;

    try {
      const res = await fetch(`/api/admin/users/${encodeURIComponent(ownerUsername)}/organization/${encodeURIComponent(ownerTargetOrg!.name)}/owner`, {
        method: 'PUT'
      });

      if (res.ok) {
        resetOwnerForm();
        ownerFormSuccess = 'Owner assigned successfully.';
        await fetchOrganizations();
        setTimeout(() => { ownerDrawerOpen = false; }, 1200);
      } else if (res.status === 404) {
        ownerFormError = 'User or organization not found. Please check the username.';
      } else if (res.status === 403) {
        ownerFormError = 'Forbidden: admin access required.';
      } else if (res.status === 401) {
        ownerFormError = 'Session expired. Please refresh the page.';
      } else {
        const body = await res.text();
        ownerFormError = `Failed to assign owner: ${body || res.statusText}`;
      }
    } catch {
      ownerFormError = 'Network error. Please try again.';
    } finally {
      ownerSubmitting = false;
    }
  }
</script>

<svelte:head>
  <title>Admin — reShapr</title>
</svelte:head>

<div class="space-y-6">
  <!-- Header -->
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold tracking-tight">Organizations</h1>
      <p class="text-sm text-muted-foreground">Manage organizations and users in the reShapr control plane.</p>
    </div>

    {#if activeTab === 'organizations'}
      <Button onclick={openOrgDrawer}>New Organization</Button>
    {:else if activeTab === 'users' && auth.authMode === 'reshapr'}
      <Button onclick={() => usersTabRef?.openUserDrawer()}>New User</Button>
    {/if}
  </div>

  <!-- Tabs -->
  <Tabs bind:value={activeTab}>
    <TabsList>
      <TabsTrigger value="organizations">
        <HugeiconsIcon icon={Building01Icon} size={16} />
        Organizations
      </TabsTrigger>
      <TabsTrigger value="users">
        <HugeiconsIcon icon={UserGroupIcon} size={16} />
        Users
      </TabsTrigger>
    </TabsList>

    <!-- ═══════════════════════════════════════════════════════ -->
    <!-- Organizations Tab                                      -->
    <!-- ═══════════════════════════════════════════════════════ -->
    <TabsContent value="organizations" class="pt-4">
      {#if orgLoading}
        <div class="flex items-center justify-center py-12">
          <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
          <span class="ml-3 text-sm text-muted-foreground">Loading organizations…</span>
        </div>
      {:else if orgFetchError}
        <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {orgFetchError}
        </div>
      {:else if allOrganizations.length === 0}
        <div class="text-center py-12 text-muted-foreground">
          No organizations found.
        </div>
      {:else}
        <div class="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Owner</TableHead>
                <TableHead class="w-25">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {#each orgDisplayed as org (org.name)}
                <TableRow>
                  <TableCell class="font-medium">{org.name}</TableCell>
                  <TableCell>{org.description ?? '—'}</TableCell>
                  <TableCell>
                    {#if org.ownerUsername}
                      <Badge variant="secondary"><HugeiconsIcon icon={UserIcon} size={12} class="mr-1" /> {org.ownerUsername}</Badge>
                    {:else}
                      —
                    {/if}
                  </TableCell>
                  <TableCell>
                    <DropdownMenu>
                      <DropdownMenuTrigger>
                        {#snippet child({ props })}
                          <Button variant="ghost" size="icon" {...props}>
                            <HugeiconsIcon icon={MoreVerticalIcon} size={16} />
                          </Button>
                        {/snippet}
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem class="whitespace-nowrap px-4" onclick={() => openOwnerDrawer(org)}>
                          <HugeiconsIcon icon={Crown} size={16} />
                          Assign owner
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                </TableRow>
              {/each}
            </TableBody>
          </Table>
        </div>

        {#if orgShowPagination}
          <div class="flex items-center justify-between pt-2">
            <p class="text-sm text-muted-foreground">Page {orgPage + 1} / {orgTotalPages}</p>
            <div class="flex gap-2">
              <Button variant="outline" size="sm" disabled={orgPage === 0} onclick={orgPreviousPage}>Previous</Button>
              <Button variant="outline" size="sm" disabled={!orgHasNextPage} onclick={orgNextPage}>Next</Button>
            </div>
          </div>
        {/if}
      {/if}
    </TabsContent>

    <!-- ═══════════════════════════════════════════════════════ -->
    <!-- Users Tab                                              -->
    <!-- ═══════════════════════════════════════════════════════ -->
    <TabsContent value="users" class="pt-4">
      <UsersTab
        bind:this={usersTabRef}
        bind:allUsers
        allOrganizations={allOrganizations}
      />
    </TabsContent>
  </Tabs>
</div>

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Create Organization Drawer                                 -->
<!-- ═══════════════════════════════════════════════════════════ -->
<Sheet bind:open={orgDrawerOpen}>
  <SheetContent side="right" class="sm:max-w-lg">
    <SheetHeader>
      <SheetTitle>Create Organization</SheetTitle>
      <SheetDescription>Register a new organization in the reShapr control plane.</SheetDescription>
    </SheetHeader>

    <form onsubmit={handleCreateOrganization} class="space-y-4 px-4 flex-1 overflow-y-auto">
      <div class="space-y-2">
        <Label for="orgName">Name <span class="text-destructive">*</span></Label>
        <Input
          id="orgName"
          type="text"
          placeholder="my_organization"
          bind:value={orgName}
          required
        />
        <p class="text-xs text-muted-foreground">
          Alphanumeric characters and underscores only.
        </p>
      </div>

      <div class="space-y-2">
        <Label for="orgDescription">Description</Label>
        <Input
          id="orgDescription"
          type="text"
          placeholder="A brief description"
          bind:value={orgDescription}
        />
      </div>

      <div class="space-y-2">
        <Label for="orgIcon">Icon URL</Label>
        <Input
          id="orgIcon"
          type="text"
          placeholder="https://example.com/icon.png"
          bind:value={orgIcon}
        />
      </div>

      {#if orgFormError}
        <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {orgFormError}
        </div>
      {/if}

      {#if orgFormSuccess}
        <div class="rounded-md bg-primary/10 px-4 py-3 text-sm text-primary">
          {orgFormSuccess}
        </div>
      {/if}

      <SheetFooter class="pt-4">
        <SheetClose>
          {#snippet child({ props })}
            <Button variant="outline" type="button" {...props}>Cancel</Button>
          {/snippet}
        </SheetClose>
        <Button type="submit" disabled={orgSubmitting || !orgName}>
          {#if orgSubmitting}
            <div class="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent"></div>
            Creating…
          {:else}
            Create Organization
          {/if}
        </Button>
      </SheetFooter>
    </form>
  </SheetContent>
</Sheet>

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Assign Owner Drawer                                        -->
<!-- ═══════════════════════════════════════════════════════════ -->
<Sheet bind:open={ownerDrawerOpen}>
  <SheetContent side="right" class="sm:max-w-lg">
    <SheetHeader>
      <SheetTitle>Assign Owner</SheetTitle>
      <SheetDescription>
        Assign a user as owner of <strong>{ownerTargetOrg?.name ?? ''}</strong>.
      </SheetDescription>
    </SheetHeader>

    <form onsubmit={handleAssignOwner} class="space-y-4 px-4 flex-1 overflow-y-auto">
      <div class="space-y-2">
        <Label for="ownerUsername">Username <span class="text-destructive">*</span></Label>
        <div class="relative">
          <Input
            id="ownerUsername"
            type="text"
            placeholder="Start typing a username…"
            bind:value={ownerUsername}
            required
            autocomplete="off"
            onfocus={() => { ownerSuggestionsOpen = true; ownerHighlightIndex = -1; }}
            onblur={() => { setTimeout(() => { ownerSuggestionsOpen = false; }, 150); }}
            oninput={() => { ownerSuggestionsOpen = true; ownerHighlightIndex = -1; }}
            onkeydown={handleOwnerInputKeydown}
          />
          {#if ownerSuggestionsOpen && ownerFilteredUsers.length > 0}
            <ul class="absolute z-50 mt-1 max-h-48 w-full overflow-y-auto rounded-md border bg-popover p-1 text-popover-foreground shadow-md">
              {#each ownerFilteredUsers.slice(0, 10) as suggestion, i (suggestion.username)}
                <li>
                  <button
                    type="button"
                    class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-sm cursor-pointer hover:bg-accent hover:text-accent-foreground {i === ownerHighlightIndex ? 'bg-accent text-accent-foreground' : ''}"
                    onmousedown={() => selectOwnerSuggestion(suggestion.username)}
                  >
                    <HugeiconsIcon icon={UserIcon} size={14} class="shrink-0 text-muted-foreground" />
                    <span class="font-medium">{suggestion.username}</span>
                    {#if suggestion.firstname || suggestion.lastname}
                      <span class="text-muted-foreground text-xs">— {suggestion.firstname ?? ''} {suggestion.lastname ?? ''}</span>
                    {/if}
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
        </div>
        <p class="text-xs text-muted-foreground">
          Select or type the username of the user to assign as owner.
        </p>
      </div>

      {#if ownerFormError}
        <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {ownerFormError}
        </div>
      {/if}

      {#if ownerFormSuccess}
        <div class="rounded-md bg-primary/10 px-4 py-3 text-sm text-primary">
          {ownerFormSuccess}
        </div>
      {/if}

      <SheetFooter class="pt-4">
        <SheetClose>
          {#snippet child({ props })}
            <Button variant="outline" type="button" {...props}>Cancel</Button>
          {/snippet}
        </SheetClose>
        <Button type="submit" disabled={ownerSubmitting || !ownerUsername}>
          {#if ownerSubmitting}
            <div class="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent"></div>
            Assigning…
          {:else}
            Assign Owner
          {/if}
        </Button>
      </SheetFooter>
    </form>
  </SheetContent>
</Sheet>
