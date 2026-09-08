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
	import { tick } from 'svelte';
	import { apiClient } from '$lib/api/client.js';
	import { formatApiError } from '$lib/format-api-error.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import PageHeader from '$lib/components/PageHeader.svelte';
	import PasswordInput from '$lib/components/PasswordInput.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import * as Select from '$lib/components/ui/select/index.js';
	import * as Table from '$lib/components/ui/table/index.js';
	import {
		Sheet,
		SheetContent,
		SheetHeader,
		SheetTitle,
		SheetDescription,
		SheetFooter,
		SheetClose
	} from '$lib/components/ui/sheet/index.js';
	import {
		DropdownMenu,
		DropdownMenuContent,
		DropdownMenuItem,
		DropdownMenuTrigger
	} from '$lib/components/ui/dropdown-menu/index.js';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import SearchIcon from '@lucide/svelte/icons/search';
	import MoreVerticalIcon from '@lucide/svelte/icons/ellipsis-vertical';
	import PencilIcon from '@lucide/svelte/icons/pencil';
	import Trash2Icon from '@lucide/svelte/icons/trash-2';
	import UserLockIcon from '@lucide/svelte/icons/user-lock';
	import KeyRoundIcon from '@lucide/svelte/icons/key-round';
	import MessageSquareLockIcon from '@lucide/svelte/icons/message-square-lock';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { RefreshIcon } from '@hugeicons/core-free-icons';
	import { cn } from '$lib/utils.js';

	type SecretRefRow = {
		id?: string;
		organizationId?: string;
		name?: string;
		description?: string;
		type?: string;
		authMethod?: string;
		username?: string;
		password?: string;
		token?: string;
		tokenHeader?: string;
		useElicitation?: boolean | string;
	};

	type SecretKind = 'simple' | 'elicitation' | 'clientCredentials';
	type SecretType = 'ARTIFACT' | 'ENDPOINT';
	type ElicitMode = 'token' | 'oauth2';
	type SimpleMode = 'basic' | 'token';

	// The authMethod discriminator value handled by the OAuth2 Client Credentials kind.
	const CLIENT_CREDENTIALS_METHOD = 'OAUTH2_CLIENT_CREDENTIALS';

	// ── List state ────────────────────────────────────────────
	let rows = $state<SecretRefRow[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let query = $state('');

	const filtered = $derived(
		query.trim() === ''
			? rows
			: rows.filter((r) => (r.name ?? '').toLowerCase().includes(query.trim().toLowerCase()))
	);

	function isElicitation(v: unknown): boolean {
		return v === true || v === 'true';
	}

	// Distinctive classification of a secret's credential kind for the table.
	type CredKind = 'basic' | 'token' | 'clientCredentials' | 'elicitation' | 'unknown';

	function credentialKind(row: SecretRefRow): CredKind {
		if (row.authMethod === CLIENT_CREDENTIALS_METHOD) return 'clientCredentials';
		if (isElicitation(row.useElicitation)) return 'elicitation';
		if ((row.username ?? '').trim() || (row.password ?? '').trim()) return 'basic';
		if ((row.token ?? '').trim() || (row.tokenHeader ?? '').trim()) return 'token';
		return 'unknown';
	}

	const CRED_META: Record<CredKind, { label: string; classes: string; icon: any }> = {
		basic: {
			label: 'User / password',
			icon: UserLockIcon,
			classes: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400'
		},
		token: {
			label: 'Token',
			icon: KeyRoundIcon,
			classes: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
		},
		clientCredentials: {
			label: 'OAuth2 client credentials',
			icon: KeyRoundIcon,
			classes: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400'
		},
		elicitation: {
			label: 'Elicitation',
			icon: MessageSquareLockIcon,
			classes: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400'
		},
		unknown: {
			label: 'Other',
			icon: KeyRoundIcon,
			classes: 'bg-muted text-muted-foreground ring-border'
		}
	};

	function asRefRows(data: unknown[]): SecretRefRow[] {
		return data.filter((r): r is SecretRefRow => r !== null && typeof r === 'object');
	}

	async function load() {
		loading = true;
		error = null;
		try {
			const data = await apiClient().listSecrets();
			rows = asRefRows(Array.isArray(data) ? data : []);
		} catch (e) {
			error = formatApiError(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void load();
	});

	// ── Create / Edit drawer state ────────────────────────────
	const KIND_LABELS: Record<SecretKind, string> = {
		simple: 'Simple secret',
		elicitation: 'Elicitation secret',
		clientCredentials: 'OAuth2 Client Credentials'
	};
	const TYPE_LABELS: Record<SecretType, string> = {
		ENDPOINT: 'Backend endpoint (ENDPOINT)',
		ARTIFACT: 'Artifact (ARTIFACT)'
	};
	const ELICIT_LABELS: Record<ElicitMode, string> = {
		token: 'Sensitive token header',
		oauth2: 'Third-party OAuth2'
	};
	const SIMPLE_LABELS: Record<SimpleMode, string> = {
		basic: 'Username / password',
		token: 'Token / token header'
	};

	let drawerOpen = $state(false);
	let editingId = $state<string | null>(null);
	let editingOrgId = $state<string | undefined>(undefined);
	let submitting = $state(false);
	let formError = $state('');
	let formSuccess = $state('');

	// Work around a bits-ui body-scroll-lock issue: on dialog close it restores the
	// captured body style ~24ms later (see bits-ui #1639). With a Select nested in the
	// Dialog, that restored style can keep `pointer-events:none`, freezing the whole
	// page so the "New secret" button no longer receives clicks. We force-unfreeze the
	// body *after* bits-ui's restore window whenever the drawer is closed.
	$effect(() => {
		if (drawerOpen) return;
		const unfreeze = () => {
			if (document.body.style.pointerEvents === 'none') {
				document.body.style.removeProperty('pointer-events');
				document.body.style.removeProperty('overflow');
			}
		};
		const timers = [50, 200].map((d) => setTimeout(unfreeze, d));
		return () => timers.forEach(clearTimeout);
	});

	// Shared fields
	let kind = $state<SecretKind>('simple');
	let fName = $state('');
	let fDescription = $state('');

	// Simple secret fields
	let fType = $state<SecretType>('ENDPOINT');
	let simpleMode = $state<SimpleMode>('basic');
	let fUsername = $state('');
	let fPassword = $state('');
	let fToken = $state('');
	let fTokenHeader = $state('');

	// Elicitation fields
	let elicitMode = $state<ElicitMode>('token');
	let fElicitTokenHeader = $state('');
	let fOauthClientId = $state('');
	let fOauthClientSecret = $state('');
	let fOauthAuthEndpoint = $state('');
	let fOauthTokenEndpoint = $state('');

	// OAuth2 Client Credentials fields (machine-to-machine, no elicitation)
	let fCcClientId = $state('');
	let fCcClientSecret = $state('');
	let fCcTokenEndpoint = $state('');
	let fCcScopes = $state('');

	function resetForm() {
		editingId = null;
		editingOrgId = undefined;
		kind = 'simple';
		fName = '';
		fDescription = '';
		fType = 'ENDPOINT';
		simpleMode = 'basic';
		fUsername = '';
		fPassword = '';
		fToken = '';
		fTokenHeader = '';
		elicitMode = 'token';
		fElicitTokenHeader = '';
		fOauthClientId = '';
		fOauthClientSecret = '';
		fOauthAuthEndpoint = '';
		fOauthTokenEndpoint = '';
		fCcClientId = '';
		fCcClientSecret = '';
		fCcTokenEndpoint = '';
		fCcScopes = '';
		formError = '';
		formSuccess = '';
	}

	function openCreate() {
		resetForm();
		void openDrawer();
	}

	// Force a clean open transition so the drawer reliably reopens even if a
	// previous user-initiated close left `drawerOpen` out of sync.
	async function openDrawer() {
		drawerOpen = false;
		await tick();
		drawerOpen = true;
	}

	async function openEdit(row: SecretRefRow) {
		resetForm();
		await openDrawer();
		if (!row.id) return;
		editingId = row.id;
		try {
			const data = (await apiClient().getSecret(row.id)) as Record<string, unknown>;
			fName = String(data.name ?? '');
			fDescription = String(data.description ?? '');
			editingOrgId = typeof data.organizationId === 'string' ? data.organizationId : undefined;
			if (data.authMethod === CLIENT_CREDENTIALS_METHOD) {
				kind = 'clientCredentials';
				const oauth = data.oauth2ClientConfiguration as Record<string, unknown> | undefined;
				if (oauth && typeof oauth === 'object') {
					fCcClientId = String(oauth.clientId ?? '');
					fCcClientSecret = String(oauth.clientSecret ?? '');
					fCcTokenEndpoint = String(oauth.tokenEndpoint ?? '');
					fCcScopes = Array.isArray(oauth.scopes) ? (oauth.scopes as unknown[]).join(', ') : '';
				}
			} else if (isElicitation(data.useElicitation)) {
				kind = 'elicitation';
				const oauth = data.oauth2ClientConfiguration as Record<string, unknown> | undefined;
				if (oauth && typeof oauth === 'object') {
					elicitMode = 'oauth2';
					fOauthClientId = String(oauth.clientId ?? '');
					fOauthClientSecret = String(oauth.clientSecret ?? '');
					fOauthAuthEndpoint = String(oauth.authorizationEndpoint ?? '');
					fOauthTokenEndpoint = String(oauth.tokenEndpoint ?? '');
				} else {
					elicitMode = 'token';
					fElicitTokenHeader = String(data.tokenHeader ?? '');
				}
			} else {
				kind = 'simple';
				fType = String(data.type ?? 'ENDPOINT').toUpperCase() === 'ARTIFACT' ? 'ARTIFACT' : 'ENDPOINT';
				fUsername = String(data.username ?? '');
				fPassword = String(data.password ?? '');
				fToken = String(data.token ?? '');
				fTokenHeader = String(data.tokenHeader ?? '');
				simpleMode = fToken && !fUsername && !fPassword ? 'token' : 'basic';
			}
		} catch (e) {
			formError = formatApiError(e);
		}
	}

	function buildBody(): Record<string, unknown> {
		const body: Record<string, unknown> = { name: fName.trim() };
		const desc = fDescription.trim();
		if (desc) body.description = desc;

		if (kind === 'clientCredentials') {
			body.type = 'ENDPOINT';
			body.authMethod = CLIENT_CREDENTIALS_METHOD;
			body.useElicitation = false;
			const scopes = fCcScopes
				.split(/[\s,]+/)
				.map((s) => s.trim())
				.filter(Boolean);
			body.oauth2ClientConfiguration = {
				clientId: fCcClientId.trim(),
				clientSecret: fCcClientSecret.trim() || undefined,
				tokenEndpoint: fCcTokenEndpoint.trim(),
				scopes: scopes.length ? scopes : undefined
			};
		} else if (kind === 'elicitation') {
			body.type = 'ENDPOINT';
			body.useElicitation = true;
			if (elicitMode === 'oauth2') {
				body.oauth2ClientConfiguration = {
					clientId: fOauthClientId.trim(),
					clientSecret: fOauthClientSecret.trim() || undefined,
					authorizationEndpoint: fOauthAuthEndpoint.trim(),
					tokenEndpoint: fOauthTokenEndpoint.trim()
				};
			} else if (fElicitTokenHeader.trim()) {
				body.tokenHeader = fElicitTokenHeader.trim();
			}
		} else {
			body.type = fType;
			if (simpleMode === 'basic') {
				if (fUsername.trim()) body.username = fUsername.trim();
				if (fPassword) body.password = fPassword;
			} else {
				if (fToken) body.token = fToken;
				if (fTokenHeader.trim()) body.tokenHeader = fTokenHeader.trim();
			}
		}

		if (editingId) {
			body.id = editingId;
			if (editingOrgId) body.organizationId = editingOrgId;
		}
		return body;
	}

	async function handleSubmit(e: Event) {
		e.preventDefault();
		formError = '';
		formSuccess = '';
		submitting = true;
		try {
			const body = buildBody();
			if (editingId) {
				await apiClient().updateSecret(editingId, body);
			} else {
				await apiClient().createSecret(body);
			}
			drawerOpen = false;
			await load();
		} catch (e) {
			formError = formatApiError(e);
		} finally {
			submitting = false;
		}
	}

	let deleteTarget = $state<SecretRefRow | null>(null);
	let deleteOpen = $state(false);

	function onDelete(row: SecretRefRow) {
		deleteTarget = row;
		deleteOpen = true;
	}

	async function confirmDeleteSecret() {
		const row = deleteTarget;
		if (!row?.id) return;
		await apiClient().deleteSecret(row.id);
		await load();
	}
</script>

<PageHeader
	title="Secrets"
	subtitle="Manage the credentials your organization uses to authenticate against backend APIs and artifacts."
>
	{#snippet actions()}
		<Button variant="outline" size="icon" title="Refresh" aria-label="Refresh" onclick={() => void load()}>
			<HugeiconsIcon icon={RefreshIcon} size={16} />
		</Button>
		<Button onclick={openCreate}>New secret</Button>
	{/snippet}
</PageHeader>

{#if error}
	<div class="mb-4">
		<ApiErrorAlert message={error} />
	</div>
{/if}

<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
	<div class="flex items-baseline gap-2">
		<h3 class="text-base font-semibold">All secrets</h3>
		{#if !loading}
			<span class="text-muted-foreground text-sm">
				{#if query.trim()}
					{filtered.length} / {rows.length}
				{:else}
					{rows.length} secret{rows.length === 1 ? '' : 's'}
				{/if}
			</span>
		{/if}
	</div>
	{#if !loading && rows.length > 0}
		<div class="relative w-full sm:w-64">
			<SearchIcon
				class="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2"
			/>
			<Input bind:value={query} placeholder="Filter by name…" class="pl-8" />
		</div>
	{/if}
</div>

{#if loading}
	<div class="text-muted-foreground rounded-lg border py-12 text-center text-sm">Loading…</div>
{:else if rows.length === 0}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No secrets yet.</p>
		<Button class="mt-3" size="sm" onclick={openCreate}>Create your first secret</Button>
	</div>
{:else if filtered.length === 0}
	<div
		class="text-muted-foreground flex flex-col items-center justify-center rounded-xl border border-dashed py-16 text-center"
	>
		<p class="text-sm">No secret matches “{query}”.</p>
	</div>
{:else}
	<div class="rounded-lg border">
		<Table.Root>
			<Table.Header>
				<Table.Row>
					<Table.Head>Name</Table.Head>
					<Table.Head>Credential</Table.Head>
					<Table.Head>Type</Table.Head>
					<Table.Head>Description</Table.Head>
					<Table.Head class="w-16 text-right">Actions</Table.Head>
				</Table.Row>
			</Table.Header>
			<Table.Body>
				{#each filtered as row (row.id ?? `${row.name}-${row.organizationId}`)}
					<Table.Row>
						<Table.Cell class="font-medium">
							<div class="flex flex-col gap-1">
								<span>{row.name ?? '—'}</span>
								<code
									class="text-muted-foreground bg-muted w-fit rounded px-1 py-0.5 font-mono text-xs break-all"
									>{row.id ?? '—'}</code
								>
							</div>
						</Table.Cell>
						<Table.Cell>
							{@const kind = credentialKind(row)}
							{@const meta = CRED_META[kind]}
							{@const Icon = meta.icon}
							<span
								class={cn(
									'inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
									meta.classes
								)}
							>
								<Icon class="size-3.5" />
								{meta.label}
							</span>
						</Table.Cell>
						<Table.Cell>
							<Badge variant="secondary" class="font-mono">{row.type ?? '—'}</Badge>
						</Table.Cell>
						<Table.Cell class="text-muted-foreground">
							{row.description?.trim() ? row.description : '—'}
						</Table.Cell>
						<Table.Cell class="text-right">
							<DropdownMenu>
								<DropdownMenuTrigger>
									{#snippet child({ props })}
										<Button variant="ghost" size="icon" {...props}>
											<MoreVerticalIcon class="size-4" />
										</Button>
									{/snippet}
								</DropdownMenuTrigger>
								<DropdownMenuContent align="end">
									<DropdownMenuItem class="px-4" onclick={() => void openEdit(row)}>
										<PencilIcon class="size-4" />
										Edit
									</DropdownMenuItem>
									<DropdownMenuItem
										class="text-destructive px-4"
										onclick={() => void onDelete(row)}
									>
										<Trash2Icon class="size-4" />
										Delete
									</DropdownMenuItem>
								</DropdownMenuContent>
							</DropdownMenu>
						</Table.Cell>
					</Table.Row>
				{/each}
			</Table.Body>
		</Table.Root>
	</div>
{/if}

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete secret"
	description={deleteTarget
		? `You are about to delete the secret "${deleteTarget.name ?? deleteTarget.id}". This action cannot be undone.`
		: undefined}
	confirmLabel="Delete"
	onConfirm={confirmDeleteSecret}
>
	<p class="text-muted-foreground text-sm">
		Any service or artifact that references this secret to authenticate against a backend API will
		stop working until you point it to another credential.
	</p>
</ConfirmDialog>

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Create / Edit Secret Drawer                                -->
<!-- ═══════════════════════════════════════════════════════════ -->
<Sheet bind:open={drawerOpen}>
	<SheetContent side="right" class="flex flex-col sm:max-w-lg">
		<SheetHeader>
			<SheetTitle>{editingId ? 'Edit secret' : 'Create secret'}</SheetTitle>
			<SheetDescription>
				{editingId
					? 'Update the configuration of this secret.'
					: 'Register a new secret in the reShapr control plane.'}
			</SheetDescription>
		</SheetHeader>

		<form onsubmit={handleSubmit} class="flex-1 space-y-4 overflow-y-auto px-4">
			<!-- Step 1: choose the kind of secret -->
			<div class="space-y-2">
				<Label for="secretKind">Secret kind <span class="text-destructive">*</span></Label>
				<Select.Root type="single" bind:value={kind}>
					<Select.Trigger id="secretKind" class="w-full">{KIND_LABELS[kind]}</Select.Trigger>
					<Select.Content>
						<Select.Item value="simple">{KIND_LABELS.simple}</Select.Item>
						<Select.Item value="clientCredentials">{KIND_LABELS.clientCredentials}</Select.Item>
						<Select.Item value="elicitation">{KIND_LABELS.elicitation}</Select.Item>
					</Select.Content>
				</Select.Root>
				<p class="text-muted-foreground text-xs">
					{#if kind === 'elicitation'}
						Elicitation secrets defer credentials to the end user at runtime (sensitive header or OAuth2).
					{:else if kind === 'clientCredentials'}
						Client Credentials secrets let the gateway obtain an OAuth2 token on its own behalf
						(machine-to-machine) to authenticate against the backend — no end-user interaction.
					{:else}
						Simple secrets store credentials (username/password, token or certificate).
					{/if}
				</p>
			</div>

			<!-- Common fields -->
			<div class="space-y-2">
				<Label for="secretName">Name <span class="text-destructive">*</span></Label>
				<Input id="secretName" placeholder="my_secret" bind:value={fName} required />
			</div>

			<div class="space-y-2">
				<Label for="secretDescription">Description</Label>
				<Input id="secretDescription" placeholder="A brief description" bind:value={fDescription} />
			</div>

			{#if kind === 'simple'}
				<div class="space-y-2">
					<Label for="secretType">Type</Label>
					<Select.Root type="single" bind:value={fType}>
						<Select.Trigger id="secretType" class="w-full">{TYPE_LABELS[fType]}</Select.Trigger>
						<Select.Content>
							<Select.Item value="ENDPOINT">{TYPE_LABELS.ENDPOINT}</Select.Item>
							<Select.Item value="ARTIFACT">{TYPE_LABELS.ARTIFACT}</Select.Item>
						</Select.Content>
					</Select.Root>
				</div>

				<div class="space-y-2">
					<Label for="simpleMode">Credentials</Label>
					<Select.Root type="single" bind:value={simpleMode}>
						<Select.Trigger id="simpleMode" class="w-full">{SIMPLE_LABELS[simpleMode]}</Select.Trigger>
						<Select.Content>
							<Select.Item value="basic">{SIMPLE_LABELS.basic}</Select.Item>
							<Select.Item value="token">{SIMPLE_LABELS.token}</Select.Item>
						</Select.Content>
					</Select.Root>
				</div>

				{#if simpleMode === 'basic'}
					<div class="grid grid-cols-2 gap-3">
						<div class="space-y-2">
							<Label for="secretUsername">Username</Label>
							<Input id="secretUsername" autocomplete="off" bind:value={fUsername} />
						</div>
						<div class="space-y-2">
							<Label for="secretPassword">Password</Label>
							<PasswordInput id="secretPassword" autocomplete="new-password" bind:value={fPassword} />
						</div>
					</div>
				{:else}
					<div class="space-y-2">
						<Label for="secretToken">Token</Label>
						<PasswordInput id="secretToken" autocomplete="off" bind:value={fToken} />
					</div>
					<div class="space-y-2">
						<Label for="secretTokenHeader">Token header</Label>
						<Input
							id="secretTokenHeader"
							placeholder="Authorization (default) or a custom header"
							bind:value={fTokenHeader}
						/>
					</div>
				{/if}
			{:else if kind === 'clientCredentials'}
				<div class="space-y-2">
					<Label for="ccClientId">Client ID <span class="text-destructive">*</span></Label>
					<Input id="ccClientId" autocomplete="off" bind:value={fCcClientId} />
				</div>
				<div class="space-y-2">
					<Label for="ccClientSecret">Client secret</Label>
					<PasswordInput id="ccClientSecret" autocomplete="new-password" bind:value={fCcClientSecret} />
					<p class="text-muted-foreground text-xs">
						You may reference an environment variable, e.g. <code>{'${env:MY_CLIENT_SECRET}'}</code>.
					</p>
				</div>
				<div class="space-y-2">
					<Label for="ccTokenEndpoint">
						Token endpoint <span class="text-destructive">*</span>
					</Label>
					<Input
						id="ccTokenEndpoint"
						placeholder="https://auth.example.com/token"
						bind:value={fCcTokenEndpoint}
					/>
				</div>
				<div class="space-y-2">
					<Label for="ccScopes">Scopes</Label>
					<Input id="ccScopes" placeholder="read write (space or comma separated)" bind:value={fCcScopes} />
					<p class="text-muted-foreground text-xs">
						Optional OAuth2 scopes requested during the client_credentials exchange.
					</p>
				</div>
			{:else}
				<div class="space-y-2">
					<Label for="elicitMode">Elicitation method</Label>
					<Select.Root type="single" bind:value={elicitMode}>
						<Select.Trigger id="elicitMode" class="w-full">{ELICIT_LABELS[elicitMode]}</Select.Trigger>
						<Select.Content>
							<Select.Item value="token">{ELICIT_LABELS.token}</Select.Item>
							<Select.Item value="oauth2">{ELICIT_LABELS.oauth2}</Select.Item>
						</Select.Content>
					</Select.Root>
				</div>

				{#if elicitMode === 'token'}
					<div class="space-y-2">
						<Label for="elicitTokenHeader">Sensitive header</Label>
						<Input
							id="elicitTokenHeader"
							placeholder="X-Api-Key"
							bind:value={fElicitTokenHeader}
						/>
						<p class="text-muted-foreground text-xs">
							The header whose value will be elicited from the end user.
						</p>
					</div>
				{:else}
					<div class="space-y-2">
						<Label for="oauthClientId">OAuth2 client ID <span class="text-destructive">*</span></Label>
						<Input id="oauthClientId" bind:value={fOauthClientId} />
					</div>
					<div class="space-y-2">
						<Label for="oauthClientSecret">OAuth2 client secret</Label>
						<PasswordInput
							id="oauthClientSecret"
							autocomplete="new-password"
							bind:value={fOauthClientSecret}
						/>
					</div>
					<div class="space-y-2">
						<Label for="oauthAuthEndpoint">
							Authorization endpoint <span class="text-destructive">*</span>
						</Label>
						<Input
							id="oauthAuthEndpoint"
							placeholder="https://auth.example.com/authorize"
							bind:value={fOauthAuthEndpoint}
						/>
					</div>
					<div class="space-y-2">
						<Label for="oauthTokenEndpoint">
							Token endpoint <span class="text-destructive">*</span>
						</Label>
						<Input
							id="oauthTokenEndpoint"
							placeholder="https://auth.example.com/token"
							bind:value={fOauthTokenEndpoint}
						/>
					</div>
				{/if}
			{/if}

			{#if formError}
				<div class="bg-destructive/10 text-destructive rounded-md px-4 py-3 text-sm">
					{formError}
				</div>
			{/if}
			{#if formSuccess}
				<div class="bg-primary/10 text-primary rounded-md px-4 py-3 text-sm">
					{formSuccess}
				</div>
			{/if}

			<SheetFooter class="pt-4">
				<SheetClose>
					{#snippet child({ props })}
						<Button variant="outline" type="button" {...props}>Cancel</Button>
					{/snippet}
				</SheetClose>
				<Button type="submit" disabled={submitting || !fName.trim()}>
					{#if submitting}
						<div
							class="border-primary-foreground h-4 w-4 animate-spin rounded-full border-2 border-t-transparent"
						></div>
						{editingId ? 'Saving…' : 'Creating…'}
					{:else}
						{editingId ? 'Save changes' : 'Create secret'}
					{/if}
				</Button>
			</SheetFooter>
		</form>
	</SheetContent>
</Sheet>
