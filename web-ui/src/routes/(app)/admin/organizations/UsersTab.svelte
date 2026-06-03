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
  import { Badge } from '$lib/components/ui/badge/index.js';
  import { Button } from '$lib/components/ui/button/index.js';
  import { Checkbox } from '$lib/components/ui/checkbox/index.js';
  import { Input } from '$lib/components/ui/input/index.js';
  import { Label } from '$lib/components/ui/label/index.js';
  import {
    Table, TableBody, TableCell, TableHead, TableHeader, TableRow
  } from '$lib/components/ui/table/index.js';
  import {
    Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose
  } from '$lib/components/ui/sheet/index.js';
  import { HugeiconsIcon } from "@hugeicons/svelte";
  import { Building01Icon, CheckmarkCircle01Icon } from '@hugeicons/core-free-icons';


  // ── Types ──────────────────────────────────────────────────
  interface User {
    username: string;
    email: string;
    firstname: string | null;
    lastname: string | null;
    defaultOrganizationName: string | null;
  }

  interface Organization {
    name: string;
    description: string | null;
    icon: string | null;
    ownerUsername: string | null;
  }

  // ── Props ─────────────────────────────────────────────────
  let {
    allUsers = $bindable([]),
    allOrganizations = [],
  }: {
    allUsers: User[];
    allOrganizations: Organization[];
  } = $props();

  // ── Pagination constants ──────────────────────────────────
  const PAGE_SIZE = 20;
  const MAX_PREFETCH_PAGES = 3;

  // ── Users state ──────────────────────────────────────────
  let userLoading = $state(true);
  let userFetchError = $state('');
  let userPage = $state(0);

  const userDisplayed = $derived(allUsers.slice(userPage * PAGE_SIZE, (userPage + 1) * PAGE_SIZE));
  const userTotalPages = $derived(Math.ceil(allUsers.length / PAGE_SIZE));
  const userShowPagination = $derived(allUsers.length > PAGE_SIZE);
  const userHasNextPage = $derived(userPage < userTotalPages - 1);

  function userPreviousPage() {
    if (userPage > 0) userPage--;
  }
  function userNextPage() {
    if (userHasNextPage) userPage++;
  }

  // ── Data fetching ────────────────────────────────────────
  async function fetchPage<T>(endpoint: string, page: number): Promise<T[] | null> {
    const res = await fetch(`${endpoint}?page=${page}&size=${PAGE_SIZE}`);
    if (!res.ok) return null;
    return await res.json() as T[];
  }

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

  async function fetchUsers() {
    userLoading = true;
    userFetchError = '';
    userPage = 0;
    try {
      const first = await fetchPage<User>('/api/admin/users', 0);
      if (first === null) {
        userFetchError = 'Failed to load users.';
        return;
      }
      allUsers = first;

      if (first.length === PAGE_SIZE) {
        prefetchPages<User>('/api/admin/users', 1, (items) => {
          allUsers = items;
        }, allUsers);
      }
    } catch {
      userFetchError = 'Network error while loading users.';
    } finally {
      userLoading = false;
    }
  }

  // ── Lifecycle ─────────────────────────────────────────────
  onMount(() => {
    fetchUsers();
  });

  // ── Create User drawer state ──────────────────────────────
  let userDrawerOpen = $state(false);
  let drawerStep = $state<'create' | 'assign-orgs'>('create');
  let username = $state('');
  let email = $state('');
  let password = $state('');
  let firstname = $state('');
  let lastname = $state('');
  let userSubmitting = $state(false);
  let userFormError = $state('');
  let userFormSuccess = $state('');

  // ── Assign organizations step state ───────────────────────
  let createdUsername = $state('');
  let selectedOrgs = $state<Set<string>>(new Set());
  let orgFilterQuery = $state('');
  let assignSubmitting = $state(false);
  let assignFormError = $state('');
  let assignFormSuccess = $state('');

  const filteredOrganizations = $derived(
    orgFilterQuery.trim() === ''
      ? allOrganizations
      : allOrganizations.filter(o => o.name.toLowerCase().includes(orgFilterQuery.toLowerCase()))
  );

  function toggleOrg(orgName: string) {
    const next = new Set(selectedOrgs);
    if (next.has(orgName)) {
      next.delete(orgName);
    } else {
      next.add(orgName);
    }
    selectedOrgs = next;
  }

  function resetUserForm() {
    username = ''; email = ''; password = '';
    firstname = ''; lastname = '';
    userFormError = ''; userFormSuccess = '';
    drawerStep = 'create';
    createdUsername = '';
    selectedOrgs = new Set();
    orgFilterQuery = '';
    assignFormError = ''; assignFormSuccess = '';
  }

  export function openUserDrawer() {
    resetUserForm();
    userDrawerOpen = true;
  }

  async function handleCreateUser(e: Event) {
    e.preventDefault();
    userFormError = '';
    userFormSuccess = '';
    userSubmitting = true;

    try {
      const res = await fetch('/api/admin/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username,
          email,
          password: password || undefined,
          firstname: firstname || undefined,
          lastname: lastname || undefined
        })
      });

      if (res.status === 201) {
        userFormSuccess = 'User created successfully.';
        createdUsername = username;
        userPage = 0;
        await fetchUsers();
        // Transition to step 2 after a short delay
        setTimeout(() => {
          drawerStep = 'assign-orgs';
          userFormSuccess = '';
        }, 800);
      } else if (res.status === 409) {
        userFormError = 'A user with this username already exists.';
      } else if (res.status === 403) {
        userFormError = 'Forbidden: admin access required.';
      } else if (res.status === 401) {
        userFormError = 'Session expired. Please refresh the page.';
      } else {
        const body = await res.text();
        userFormError = `Failed to create user: ${body || res.statusText}`;
      }
    } catch {
      userFormError = 'Network error. Please try again.';
    } finally {
      userSubmitting = false;
    }
  }

  async function handleAssignMemberships(e: Event) {
    e.preventDefault();
    assignFormError = '';
    assignFormSuccess = '';
    assignSubmitting = true;

    try {
      const res = await fetch(`/api/admin/users/${encodeURIComponent(createdUsername)}/memberships`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([...selectedOrgs])
      });

      if (res.ok) {
        assignFormSuccess = 'Memberships assigned successfully.';
        await fetchUsers();
        setTimeout(() => { userDrawerOpen = false; }, 1200);
      } else if (res.status === 404) {
        assignFormError = 'User not found.';
      } else if (res.status === 403) {
        assignFormError = 'Forbidden: admin access required.';
      } else if (res.status === 401) {
        assignFormError = 'Session expired. Please refresh the page.';
      } else {
        const body = await res.text();
        assignFormError = `Failed to assign memberships: ${body || res.statusText}`;
      }
    } catch {
      assignFormError = 'Network error. Please try again.';
    } finally {
      assignSubmitting = false;
    }
  }

  function skipAssignOrgs() {
    userDrawerOpen = false;
  }
</script>

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Users Tab Content                                          -->
<!-- ═══════════════════════════════════════════════════════════ -->
{#if userLoading}
  <div class="flex items-center justify-center py-12">
    <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
    <span class="ml-3 text-sm text-muted-foreground">Loading users…</span>
  </div>
{:else if userFetchError}
  <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
    {userFetchError}
  </div>
{:else if allUsers.length === 0}
  <div class="text-center py-12 text-muted-foreground">
    No users found.
  </div>
{:else}
  <div class="rounded-md border">
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Username</TableHead>
          <TableHead>Email</TableHead>
          <TableHead>First name</TableHead>
          <TableHead>Last name</TableHead>
          <TableHead>Default Organization</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {#each userDisplayed as user (user.username)}
          <TableRow>
            <TableCell class="font-medium">{user.username}</TableCell>
            <TableCell>{user.email ?? '—'}</TableCell>
            <TableCell>{user.firstname ?? '—'}</TableCell>
            <TableCell>{user.lastname ?? '—'}</TableCell>
            <TableCell>
              {#if user.defaultOrganizationName}
                <Badge variant="outline"><HugeiconsIcon icon={Building01Icon} size={12} class="mr-1" /> {user.defaultOrganizationName}</Badge>
              {:else}
                —
              {/if}
            </TableCell>
          </TableRow>
        {/each}
      </TableBody>
    </Table>
  </div>

  {#if userShowPagination}
    <div class="flex items-center justify-between pt-2">
      <p class="text-sm text-muted-foreground">Page {userPage + 1} / {userTotalPages}</p>
      <div class="flex gap-2">
        <Button variant="outline" size="sm" disabled={userPage === 0} onclick={userPreviousPage}>Previous</Button>
        <Button variant="outline" size="sm" disabled={!userHasNextPage} onclick={userNextPage}>Next</Button>
      </div>
    </div>
  {/if}
{/if}

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Create User Drawer (multi-step)                            -->
<!-- ═══════════════════════════════════════════════════════════ -->
<Sheet bind:open={userDrawerOpen}>
  <SheetContent side="right" class="sm:max-w-lg">
    <!-- Step indicator -->
    <div class="flex items-center gap-2 px-4 pt-4 pb-2">
      <div class="flex items-center gap-1.5 text-xs font-medium {drawerStep === 'create' ? 'text-primary' : 'text-muted-foreground'}">
        <span class="flex h-5 w-5 items-center justify-center rounded-full {drawerStep === 'create' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}">
          {#if drawerStep === 'assign-orgs'}
            <HugeiconsIcon icon={CheckmarkCircle01Icon} size={14} />
          {:else}
            1
          {/if}
        </span>
        Create
      </div>
      <div class="h-px w-6 bg-border"></div>
      <div class="flex items-center gap-1.5 text-xs font-medium {drawerStep === 'assign-orgs' ? 'text-primary' : 'text-muted-foreground'}">
        <span class="flex h-5 w-5 items-center justify-center rounded-full {drawerStep === 'assign-orgs' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'}">2</span>
        Organizations
      </div>
    </div>

    {#if drawerStep === 'create'}
      <!-- ─── Step 1: Create User ─────────────────────────────── -->
      <SheetHeader>
        <SheetTitle>Create User</SheetTitle>
        <SheetDescription>Register a new user in the reShapr control plane.</SheetDescription>
      </SheetHeader>

      <form onsubmit={handleCreateUser} class="space-y-4 px-4 flex-1 overflow-y-auto">
        <div class="space-y-2">
          <Label for="username">Username <span class="text-destructive">*</span></Label>
          <Input
            id="username"
            type="text"
            placeholder="janedoe"
            bind:value={username}
            required
            autocomplete="username"
          />
        </div>

        <div class="space-y-2">
          <Label for="email">Email <span class="text-destructive">*</span></Label>
          <Input
            id="email"
            type="email"
            placeholder="jane@example.com"
            bind:value={email}
            required
            autocomplete="email"
          />
        </div>

        <div class="space-y-2">
          <Label for="password">Password</Label>
          <Input
            id="password"
            type="password"
            placeholder="Leave empty if using OIDC"
            bind:value={password}
            autocomplete="new-password"
          />
          <p class="text-xs text-muted-foreground">
            Only required when the control plane handles authentication directly (no OIDC).
          </p>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div class="space-y-2">
            <Label for="firstname">First name</Label>
            <Input
              id="firstname"
              type="text"
              placeholder="Jane"
              bind:value={firstname}
              autocomplete="given-name"
            />
          </div>
          <div class="space-y-2">
            <Label for="lastname">Last name</Label>
            <Input
              id="lastname"
              type="text"
              placeholder="Doe"
              bind:value={lastname}
              autocomplete="family-name"
            />
          </div>
        </div>

        {#if userFormError}
          <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {userFormError}
          </div>
        {/if}

        {#if userFormSuccess}
          <div class="rounded-md bg-primary/10 px-4 py-3 text-sm text-primary">
            {userFormSuccess}
          </div>
        {/if}

        <SheetFooter class="pt-4">
          <SheetClose>
            {#snippet child({ props })}
              <Button variant="outline" type="button" {...props}>Cancel</Button>
            {/snippet}
          </SheetClose>
          <Button type="submit" disabled={userSubmitting || !username || !email}>
            {#if userSubmitting}
              <div class="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent"></div>
              Creating…
            {:else}
              Create User
            {/if}
          </Button>
        </SheetFooter>
      </form>

    {:else if drawerStep === 'assign-orgs'}
      <!-- ─── Step 2: Assign Organizations ────────────────────── -->
      <SheetHeader>
        <SheetTitle>Assign Organizations</SheetTitle>
        <SheetDescription>
          Select the organizations that <strong>{createdUsername}</strong> should be a member of.
        </SheetDescription>
      </SheetHeader>

      <form onsubmit={handleAssignMemberships} class="space-y-4 px-4 flex-1 overflow-y-auto">
        <div class="space-y-2">
          <Label for="orgFilter">Filter organizations</Label>
          <Input
            id="orgFilter"
            type="text"
            placeholder="Search by name…"
            bind:value={orgFilterQuery}
            autocomplete="off"
          />
        </div>

        {#if allOrganizations.length === 0}
          <div class="text-center py-6 text-muted-foreground text-sm">
            No organizations available.
          </div>
        {:else}
          <div class="rounded-md border max-h-64 overflow-y-auto">
            <ul class="divide-y">
              {#each filteredOrganizations as org (org.name)}
                <li>
                  <button
                    type="button"
                    class="flex w-full items-center gap-3 px-3 py-2.5 text-sm hover:bg-accent/50 transition-colors cursor-pointer {selectedOrgs.has(org.name) ? 'bg-accent/30' : ''}"
                    onclick={() => toggleOrg(org.name)}
                  >
                    <Checkbox checked={selectedOrgs.has(org.name)} />
                    <HugeiconsIcon icon={Building01Icon} size={14} class="shrink-0 text-muted-foreground" />
                    <span class="font-medium">{org.name}</span>
                    {#if org.description}
                      <span class="text-muted-foreground text-xs truncate">— {org.description}</span>
                    {/if}
                  </button>
                </li>
              {/each}
            </ul>
          </div>

          {#if selectedOrgs.size > 0}
            <p class="text-xs text-muted-foreground">
              {selectedOrgs.size} organization{selectedOrgs.size > 1 ? 's' : ''} selected
            </p>
          {/if}
        {/if}

        {#if assignFormError}
          <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {assignFormError}
          </div>
        {/if}

        {#if assignFormSuccess}
          <div class="rounded-md bg-primary/10 px-4 py-3 text-sm text-primary">
            {assignFormSuccess}
          </div>
        {/if}

        <SheetFooter class="pt-4">
          <Button variant="outline" type="button" onclick={skipAssignOrgs}>Skip</Button>
          <Button type="submit" disabled={assignSubmitting || selectedOrgs.size === 0}>
            {#if assignSubmitting}
              <div class="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent"></div>
              Assigning…
            {:else}
              Assign Memberships
            {/if}
          </Button>
        </SheetFooter>
      </form>
    {/if}
  </SheetContent>
</Sheet>

