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
  import PageHeader from '#lib/components/PageHeader.svelte';
  import { auth } from '#lib/stores/auth.svelte.js';
  import * as Alert from '#lib/components/ui/alert/index.js';
  import * as Card from '#lib/components/ui/card/index.js';
  import * as Table from '#lib/components/ui/table/index.js';
  import { Button } from '#lib/components/ui/button/index.js';
  import { Input } from '#lib/components/ui/input/index.js';
  import { Label } from '#lib/components/ui/label/index.js';
  import { onMount } from 'svelte';
  import { HugeiconsIcon } from '@hugeicons/svelte';
  import { Building01Icon, PlusSignIcon, Delete01Icon } from '@hugeicons/core-free-icons';
  import ConfirmDialog from '#lib/components/ConfirmDialog.svelte';

  interface MemberDTO {
    username: string;
    email: string;
    firstname: string;
    lastname: string;
  }

  let members = $state<MemberDTO[]>([]);
  let isLoading = $state(true);
  let errorMsg = $state<string | null>(null);
  let isOwner = $state(true);

  // Invite member state
  let inviteEmail = $state('');
  let isInviting = $state(false);
  let inviteError = $state<string | null>(null);
  let inviteSuccess = $state<string | null>(null);

  // Remove member state
  let memberToRemove = $state<MemberDTO | null>(null);
  let isConfirmDialogOpen = $state(false);

  async function fetchMembers() {
    if (!auth.user?.org) return;

    isLoading = true;
    errorMsg = null;
    isOwner = true;

    try {
      const res = await fetch(`/api/v1/organizations/${encodeURIComponent(auth.user.org)}/members`);
      if (res.status === 403) {
        isOwner = false;
      } else if (!res.ok) {
        throw new Error(await res.text());
      } else {
        members = await res.json();
      }
    } catch (e: unknown) {
      errorMsg = (e instanceof Error && e.message) || 'Failed to fetch members.';
    } finally {
      isLoading = false;
    }
  }

  async function addMember(e: Event) {
    e.preventDefault();
    if (!inviteEmail || !auth.user?.org) return;

    isInviting = true;
    inviteError = null;
    inviteSuccess = null;

    try {
      const res = await fetch(`/api/v1/organizations/${encodeURIComponent(auth.user.org)}/members`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: inviteEmail })
      });

      if (!res.ok) {
        throw new Error(await res.text());
      }

      inviteSuccess = 'User successfully added to organization.';
      inviteEmail = '';
      await fetchMembers();
    } catch (e: unknown) {
      inviteError = (e instanceof Error && e.message) || 'Failed to add member.';
    } finally {
      isInviting = false;
    }
  }

  async function removeMember() {
    if (!memberToRemove || !auth.user?.org) return;

    const res = await fetch(`/api/v1/organizations/${encodeURIComponent(auth.user.org)}/members/${encodeURIComponent(memberToRemove.email)}`, {
      method: 'DELETE'
    });

    if (!res.ok) {
      throw new Error(await res.text());
    }

    memberToRemove = null;
    isConfirmDialogOpen = false;
    await fetchMembers();
  }

  onMount(() => {
    if (auth.user?.org) {
      fetchMembers();
    } else {
      isLoading = false;
    }
  });

  // Whenever the active org changes, re-fetch.
  $effect(() => {
    if (auth.user?.org) {
      fetchMembers();
    }
  });
</script>

<svelte:head>
  <title>Organization Settings — reShapr</title>
</svelte:head>

<div>
  <PageHeader
    title="Organization Settings"
    subtitle="Manage members and settings for your current organization."
  />

  {#if !auth.user?.org}
    <Alert.Root variant="destructive">
      <Alert.Title>No organization selected</Alert.Title>
      <Alert.Description class="text-sm">
        Please select an organization from the sidebar or user menu.
      </Alert.Description>
    </Alert.Root>
  {:else if !isOwner}
    <Alert.Root class="mb-6">
      <Alert.Title class="flex items-center gap-2">
        {#if auth.currentOrgIcon && auth.currentOrgIcon.startsWith('http')}
          <img src={auth.currentOrgIcon} alt="Logo" class="h-5 w-5 object-cover rounded-sm" />
        {:else}
          <HugeiconsIcon icon={Building01Icon} size={18} />
        {/if}
        {auth.user.org}
      </Alert.Title>
      <Alert.Description class="text-sm mt-2">
        You are a member of this organization, but only the owner can manage its settings and members.
      </Alert.Description>
    </Alert.Root>
  {:else}
    <div class="grid gap-6 lg:grid-cols-[1fr_300px]">
      
      <!-- Members List -->
      <Card.Root>
        <Card.Header>
          <Card.Title class="flex items-center gap-2 text-lg">
            {#if auth.currentOrgIcon && auth.currentOrgIcon.startsWith('http')}
              <img src={auth.currentOrgIcon} alt="Logo" class="h-6 w-6 object-cover rounded-sm" />
            {:else}
              <HugeiconsIcon icon={Building01Icon} size={20} />
            {/if}
            Members of {auth.user.org}
          </Card.Title>
          <Card.Description>Manage who has access to this organization.</Card.Description>
        </Card.Header>
        <Card.Content>
          {#if errorMsg}
            <Alert.Root variant="destructive" class="mb-4">
              <Alert.Title>Error</Alert.Title>
              <Alert.Description class="text-sm">{errorMsg}</Alert.Description>
            </Alert.Root>
          {/if}

          {#if isLoading}
            <div class="text-sm text-muted-foreground p-4 text-center">Loading members...</div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>Name</Table.Head>
                  <Table.Head>Email</Table.Head>
                  <Table.Head>Username</Table.Head>
                  <Table.Head class="w-[80px] text-right"></Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each members as member}
                  <Table.Row>
                    <Table.Cell class="font-medium">
                      {member.firstname || ''} {member.lastname || ''}
                      {#if !member.firstname && !member.lastname}
                        <span class="text-muted-foreground italic">No name provided</span>
                      {/if}
                    </Table.Cell>
                    <Table.Cell>{member.email}</Table.Cell>
                    <Table.Cell>{member.username}</Table.Cell>
                    <Table.Cell class="text-right">
                      {#if member.username !== auth.user.username}
                        <Button 
                          variant="ghost" 
                          size="icon" 
                          class="h-8 w-8 text-destructive"
                          onclick={() => { memberToRemove = member; isConfirmDialogOpen = true; }}
                        >
                          <HugeiconsIcon icon={Delete01Icon} size={16} />
                        </Button>
                      {/if}
                    </Table.Cell>
                  </Table.Row>
                {/each}
                {#if members.length === 0}
                  <Table.Row>
                    <Table.Cell colspan={4} class="text-center text-muted-foreground py-6">
                      No members found.
                    </Table.Cell>
                  </Table.Row>
                {/if}
              </Table.Body>
            </Table.Root>
          {/if}
        </Card.Content>
      </Card.Root>

      <!-- Invite Panel -->
      <div class="space-y-6">
        <Card.Root>
          <Card.Header>
            <Card.Title class="text-base">Add Member</Card.Title>
            <Card.Description>Add an existing user to this organization by email.</Card.Description>
          </Card.Header>
          <Card.Content>
            <form class="space-y-4" onsubmit={addMember}>
              {#if inviteError}
                <Alert.Root variant="destructive">
                  <Alert.Description class="text-sm">{inviteError}</Alert.Description>
                </Alert.Root>
              {/if}
              
              {#if inviteSuccess}
                <Alert.Root class="border-green-500/50 text-green-600 dark:text-green-400">
                  <Alert.Description class="text-sm">{inviteSuccess}</Alert.Description>
                </Alert.Root>
              {/if}

              <div class="space-y-2">
                <Label for="email">User Email</Label>
                <Input 
                  id="email" 
                  type="email" 
                  placeholder="name@example.com" 
                  bind:value={inviteEmail} 
                  required
                />
              </div>

              <Button type="submit" class="w-full" disabled={isInviting || !inviteEmail}>
                {#if isInviting}
                  Adding...
                {:else}
                  <HugeiconsIcon icon={PlusSignIcon} size={16} class="mr-2" />
                  Add Member
                {/if}
              </Button>
            </form>
          </Card.Content>
        </Card.Root>
      </div>

    </div>
  {/if}
</div>

<ConfirmDialog
  bind:open={isConfirmDialogOpen}
  title="Remove Member"
  description="Are you sure you want to remove {memberToRemove?.email} from the organization?"
  confirmLabel="Remove"
  variant="destructive"
  onConfirm={removeMember}
  onCancel={() => {
    memberToRemove = null;
  }}
/>
