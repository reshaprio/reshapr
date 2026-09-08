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
	import { goto } from '$app/navigation';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import {
		buildDefaultArtifactTitle,
		buildReferenceIndex,
		extractKindFromYaml,
		getExamplesForKind,
		getKindDefinition,
		hasValidators,
		insertExample,
		runValidators,
		saveCustomArtifact,
		type ArtifactExample,
		type EditorMode,
		type ReshaprArtifactKind,
		type ReshaprReferenceIndex,
		type ServiceRef,
		type ValidatorWarning
	} from '$lib/artifacts/index.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import YamlMonacoEditor from '$lib/components/YamlMonacoEditor.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import {
		Tooltip,
		TooltipContent,
		TooltipProvider,
		TooltipTrigger
	} from '$lib/components/ui/tooltip/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { PlusSignIcon } from '@hugeicons/core-free-icons';
	import PencilIcon from '@lucide/svelte/icons/pencil';
	import CheckIcon from '@lucide/svelte/icons/check';
	import type * as Monaco from 'monaco-editor';

	const MONACO_WARNING_SEVERITY = 4;

	let {
		mode,
		kind,
		initialContent,
		listHref,
		artifactName = undefined,
		existingNames = [],
		service = undefined,
		serviceId = undefined
	}: {
		mode: EditorMode;
		kind: ReshaprArtifactKind;
		initialContent: string;
		listHref: string;
		artifactName?: string;
		existingNames?: string[];
		service?: ServiceRef;
		serviceId?: string;
	} = $props();

	let content = $state('');
	let contentSeed = $state('');
	let validationMarkers = $state<Monaco.editor.IMarker[]>([]);
	let saveError = $state<string | null>(null);
	let saving = $state(false);
	let editorRef = $state<YamlMonacoEditor | null>(null);

	// Custom (reShapr) validator findings. Warning-severity ones are non-blocking (yellow); error-severity
	// ones (e.g. undeclared `${var}` placeholders, also rejected by the control plane at import) are
	// blocking and gate saving — alongside schema errors.
	let validatorWarnings = $state<ValidatorWarning[]>([]);

	let title = $state('');
	let titleDirty = $state(false);
	let editingTitle = $state(false);
	let titleInputRef = $state<HTMLInputElement | null>(null);

	const kindDef = $derived(getKindDefinition(kind));
	const editable = $derived(mode === 'create' || mode === 'edit');
	const schemaUri = $derived(editable ? kindDef?.schemaPath : undefined);
	const examples = $derived(editable ? getExamplesForKind(kind) : []);
	const serviceRef = $derived<ServiceRef>(service ?? { name: '—', version: '—' });
	const schemaErrors = $derived(
		validationMarkers.filter((marker) => marker.severity >= MONACO_WARNING_SEVERITY)
	);
	const blockingValidatorErrors = $derived(
		validatorWarnings.filter((warning) => warning.severity === 'error')
	);
	const canSave = $derived(
		editable &&
			!saving &&
			content.trim().length > 0 &&
			schemaErrors.length === 0 &&
			blockingValidatorErrors.length === 0
	);

	// ── Custom (reShapr) validators ──────────────────────────────────────────────────────────────
	let referenceIndexPromise: Promise<ReshaprReferenceIndex> | null = null;
	let validationRunId = 0;

	function loadReferenceIndex(): Promise<ReshaprReferenceIndex> {
		if (!referenceIndexPromise) {
			referenceIndexPromise = buildReferenceIndex(apiClient(), serviceId);
		}
		return referenceIndexPromise;
	}

	async function runCustomValidators(currentContent: string) {
		const runId = ++validationRunId;
		if (!editable || !hasValidators(kind)) {
			validatorWarnings = [];
			return;
		}
		try {
			const warnings = await runValidators({
				content: currentContent,
				kind,
				service: serviceRef,
				serviceId,
				api: apiClient(),
				loadReferenceIndex
			});
			if (runId === validationRunId) validatorWarnings = warnings;
		} catch {
			if (runId === validationRunId) validatorWarnings = [];
		}
	}

	// Debounced re-validation whenever the document changes.
	$effect(() => {
		const currentContent = content;
		if (!editable || !hasValidators(kind)) {
			validatorWarnings = [];
			return;
		}
		const timer = setTimeout(() => void runCustomValidators(currentContent), 400);
		return () => clearTimeout(timer);
	});

	// Reflect warnings into the editor as yellow markers once the editor is mounted.
	$effect(() => {
		editorRef?.setValidatorMarkers(validatorWarnings);
	});

	const defaultTitle = $derived(
		mode === 'create'
			? buildDefaultArtifactTitle(kind, existingNames)
			: (artifactName ?? 'Artifact')
	);

	// Keep the title in sync with the default until the user edits it manually.
	$effect(() => {
		if (!titleDirty) title = defaultTitle;
	});

	// Focus and select the input when entering title edit mode.
	$effect(() => {
		if (editingTitle && titleInputRef) {
			titleInputRef.focus();
			titleInputRef.select();
		}
	});

	$effect(() => {
		if (initialContent === contentSeed) return;
		contentSeed = initialContent;
		content = initialContent;
	});

	function startEditTitle() {
		editingTitle = true;
	}

	function commitTitle() {
		const trimmed = title.trim();
		if (!trimmed) {
			titleDirty = false;
			title = defaultTitle;
		} else {
			title = trimmed;
			titleDirty = true;
		}
		editingTitle = false;
	}

	function cancelTitle() {
		editingTitle = false;
	}

	function onTitleKeydown(event: KeyboardEvent) {
		if (event.key === 'Enter') {
			event.preventDefault();
			commitTitle();
		} else if (event.key === 'Escape') {
			event.preventDefault();
			cancelTitle();
		}
	}

	function onInsertExample(example: ArtifactExample) {
		const next = insertExample(content, kind, example, serviceRef);
		content = next;
		editorRef?.setValue(next);
	}

	async function onSave() {
		if (!canSave) return;
		saveError = null;

		const extractedKind = extractKindFromYaml(content);
		if (extractedKind && extractedKind !== kind) {
			saveError = `YAML kind must be "${kind}", got "${extractedKind}".`;
			return;
		}
		if (!extractedKind) {
			saveError = `Could not read kind: from YAML. Expected kind: ${kind}.`;
			return;
		}

		saving = true;
		try {
			await saveCustomArtifact(apiClient(), content, kind, title.trim() || undefined);
			await goto(listHref);
		} catch (e) {
			saveError = e instanceof ApiError ? e.message : String(e);
		} finally {
			saving = false;
		}
	}
</script>

<div class="mb-4 flex min-h-9 items-center gap-2">
	{#if mode === 'create' && editingTitle}
		<Input
			bind:ref={titleInputRef}
			bind:value={title}
			class="h-9 max-w-md text-lg font-semibold"
			aria-label="Artifact title"
			onkeydown={onTitleKeydown}
			onblur={commitTitle}
		/>
		<Button
			variant="ghost"
			size="icon"
			class="size-8"
			aria-label="Confirm title"
			onmousedown={(e) => e.preventDefault()}
			onclick={commitTitle}
		>
			<CheckIcon class="size-4" />
		</Button>
	{:else}
		<h2 class="text-lg font-semibold break-all">{title}</h2>
		{#if mode === 'create'}
			<Button
				variant="ghost"
				size="icon"
				class="size-8"
				aria-label="Edit title"
				onclick={startEditTitle}
			>
				<PencilIcon class="size-4" />
			</Button>
		{/if}
	{/if}
</div>

{#if saveError}
	<div class="mb-4">
		<ApiErrorAlert message={saveError} />
	</div>
{/if}

<div class="flex flex-col gap-3 lg:flex-row lg:items-stretch">
	<div class="min-w-0 flex-1">
		<YamlMonacoEditor
			bind:this={editorRef}
			value={content}
			readOnly={!editable}
			{schemaUri}
			warnings={editable ? validatorWarnings : []}
			height="min(70vh, 32rem)"
			onChange={(value) => {
				content = value;
			}}
			onValidationChange={(markers) => {
				validationMarkers = markers;
			}}
		/>
	</div>

	{#if editable && examples.length > 0}
		<aside class="lg:w-64 lg:shrink-0" aria-label="Insert an example">
			<div class="bg-muted/40 h-full rounded-lg border p-3">
				<h3 class="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
					Start from an example
				</h3>
				<p class="text-muted-foreground mb-3 text-xs">
					Insert a ready-made snippet into the editor, then adapt it to your service.
				</p>
				<TooltipProvider delayDuration={200}>
					<div class="flex flex-col gap-2">
						{#each examples as example (example.id)}
							<Tooltip>
								<TooltipTrigger>
									{#snippet child({ props })}
										<button
											type="button"
											{...props}
											onclick={() => onInsertExample(example)}
											class="border-border bg-background hover:border-primary/50 hover:bg-accent focus-visible:ring-ring flex w-full items-center gap-2 rounded-md border p-3 text-left text-sm transition-colors focus-visible:ring-2 focus-visible:outline-none"
										>
											<span
												class="bg-primary/10 text-primary flex size-8 shrink-0 items-center justify-center rounded-md"
											>
												<HugeiconsIcon icon={PlusSignIcon} size={18} />
											</span>
											<span class="min-w-0 font-medium">{example.label}</span>
										</button>
									{/snippet}
								</TooltipTrigger>
								<TooltipContent side="left" class="max-w-xs">
									<span class="text-xs">{example.description}</span>
								</TooltipContent>
							</Tooltip>
						{/each}
					</div>
				</TooltipProvider>
			</div>
		</aside>
	{/if}
</div>

{#if editable}
	<div class="mt-4 flex flex-wrap items-center gap-2">
		<Button disabled={!canSave} onclick={() => void onSave()}>
			{saving ? 'Saving…' : 'Save'}
		</Button>
		<Button variant="outline" href={listHref} disabled={saving}>Cancel</Button>
	</div>
{/if}
