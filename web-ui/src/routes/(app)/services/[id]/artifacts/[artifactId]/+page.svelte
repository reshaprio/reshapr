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
	import { getContext } from 'svelte';
	import { page } from '$app/state';
	import { apiClient, ApiError } from '#lib/api/client.js';
	import {
		buildTemplate,
		getKindDefinition,
		getKindForArtifactType,
		isEditableArtifactType,
		parseArtifactDetail,
		parseArtifactRefList,
		type ArtifactDetail,
		type EditorMode,
		type ReshaprArtifactKind
	} from '#lib/artifacts/index.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import CustomArtifactEditor from '#lib/components/CustomArtifactEditor.svelte';
	import { SERVICE_CONTEXT_KEY, type ServiceContextValue } from '#lib/serviceContext.js';
	import { Button } from '#lib/components/ui/button/index.js';

	const ctx = getContext<ServiceContextValue>(SERVICE_CONTEXT_KEY);

	const artifactId = $derived(page.params.artifactId ?? '');
	const isCreate = $derived(artifactId === 'new');
	const createKind = $derived(
		(page.url.searchParams.get('kind') as ReshaprArtifactKind | null) ?? 'Prompts'
	);

	let error = $state<string | null>(null);
	let loading = $state(false);
	let artifact = $state<ArtifactDetail | null>(null);
	let existingNames = $state<string[]>([]);

	const listHref = $derived(`/services/${ctx.id}/artifacts`);

	const mode = $derived.by((): EditorMode => {
		if (isCreate) return 'create';
		if (artifact && isEditableArtifactType(artifact.type)) return 'edit';
		return 'view';
	});

	const kind = $derived.by((): ReshaprArtifactKind => {
		if (isCreate) return createKind;
		return getKindForArtifactType(artifact?.type ?? 'JSON_FRAGMENT')?.kind ?? createKind;
	});

	const initialContent = $derived.by((): string => {
		if (isCreate) {
			return buildTemplate(createKind, {
				name: ctx.service?.name ?? '—',
				version: ctx.service?.version ?? '—'
			});
		}
		return artifact?.content ?? '';
	});

	const createKindDef = $derived(getKindDefinition(createKind));

	async function loadArtifact() {
		if (isCreate || !artifactId) return;
		loading = true;
		error = null;
		artifact = null;
		try {
			const raw = await apiClient().getArtifact(artifactId);
			const detail = parseArtifactDetail(raw);
			if (!detail) {
				error = 'Artifact not found or invalid response.';
				return;
			}
			artifact = detail;
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading && !isCreate && artifactId) void loadArtifact();
	});

	async function loadExistingNames() {
		if (!isCreate || !ctx.id) return;
		try {
			const list = await apiClient().listArtifactRefsByService(ctx.id);
			existingNames = parseArtifactRefList(list).map((ref) => ref.name);
		} catch {
			existingNames = [];
		}
	}

	$effect(() => {
		if (ctx.id && !ctx.loading && isCreate) void loadExistingNames();
	});
</script>

<div class="mb-4 flex flex-wrap items-center justify-between gap-4">
	<h3 class="text-lg font-semibold">
		{#if isCreate}
			New custom artifact
		{:else}
			Artifact detail
		{/if}
	</h3>
	<Button variant="outline" size="sm" href={listHref}>Back to list</Button>
</div>

{#if error}
	<ApiErrorAlert message={error} />
{:else if isCreate && !createKindDef}
	<ApiErrorAlert message="Unknown artifact kind. Pick a kind from the Artifacts list." />
{:else if isCreate || (!loading && artifact)}
	{#key `${artifactId}-${kind}-${artifact?.id ?? 'new'}`}
		<CustomArtifactEditor
			{mode}
			{kind}
			{initialContent}
			{listHref}
			artifactName={artifact?.name}
			{existingNames}
			service={ctx.service
				? { name: ctx.service.name, version: ctx.service.version }
				: undefined}
			serviceId={ctx.id}
		/>
	{/key}
{:else if loading}
	<p class="text-muted-foreground text-sm">Loading artifact content…</p>
{/if}
