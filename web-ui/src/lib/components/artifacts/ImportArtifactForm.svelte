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

<script lang="ts" module>
	export type ImportArtifactMode = 'import' | 'attach';
</script>

<script lang="ts">
	import { apiClient, ApiError } from '#lib/api/client.js';
	import ApiErrorAlert from '#lib/components/ApiErrorAlert.svelte';
	import ArtifactSecretSelect from './ArtifactSecretSelect.svelte';
	import { Input } from '#lib/components/ui/input/index.js';
	import { Label } from '#lib/components/ui/label/index.js';
	import { Tabs, TabsContent, TabsList, TabsTrigger } from '#lib/components/ui/tabs/index.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { FileUploadIcon, Link01Icon } from '@hugeicons/core-free-icons';
	import { SvelteURLSearchParams } from 'svelte/reactivity';

	/**
	 * Embeddable import/attach form (no dialog, no action button). It exposes an
	 * `async submit()` method returning the API response (or `null` on validation
	 * error) so hosts control when the submission happens and where the action
	 * button lives. Reused by {@link ImportArtifactDialog} and the Quick start
	 * wizard.
	 */
	let {
		mode,
		onDone,
		// eslint-disable-next-line no-useless-assignment -- read externally via bind:submitting
		submitting = $bindable(false)
	}: {
		mode: ImportArtifactMode;
		onDone?: (result: unknown) => void;
		submitting?: boolean;
	} = $props();

	type Source = 'file' | 'url';

	let source = $state<Source>('file');
	let files = $state<FileList | undefined>(undefined);
	let url = $state('');
	let secretName = $state('');
	let serviceName = $state('');
	let serviceVersion = $state('');
	let error = $state<string | null>(null);

	export function reset() {
		source = 'file';
		files = undefined;
		url = '';
		secretName = '';
		serviceName = '';
		serviceVersion = '';
		error = null;
	}

	/**
	 * Perform the import/attach call. Returns the API response on success, or
	 * `null` when validation failed or the request errored (the error is shown
	 * inline).
	 */
	export async function submit(): Promise<unknown | null> {
		error = null;
		submitting = true;
		try {
			const client = apiClient();
			let result: unknown;

			if (source === 'file') {
				const file = files?.[0];
				if (!file || !file.size) {
					error = 'Please choose a file.';
					return null;
				}
				if (mode === 'import') {
					const extra: Record<string, string> = {};
					if (serviceName.trim()) extra.serviceName = serviceName.trim();
					if (serviceVersion.trim()) extra.serviceVersion = serviceVersion.trim();
					result = await client.importArtifactFile(file, extra);
				} else {
					result = await client.attachArtifactFile(file);
				}
			} else {
				const u = url.trim();
				if (!u) {
					error = 'Please provide a URL.';
					return null;
				}
				if (mode === 'import') {
					const p = new SvelteURLSearchParams();
					p.set('url', u);
					p.set('mainArtifact', 'true');
					if (secretName.trim()) p.set('secretName', secretName.trim());
					if (serviceName.trim()) p.set('serviceName', serviceName.trim());
					if (serviceVersion.trim()) p.set('serviceVersion', serviceVersion.trim());
					result = await client.importArtifactUrl(p);
				} else {
					result = await client.attachArtifactUrl(u, secretName.trim() || undefined);
				}
			}

			onDone?.(result);
			reset();
			return result;
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
			return null;
		} finally {
			submitting = false;
		}
	}
</script>

<div class="space-y-4">
	{#if error}
		<ApiErrorAlert message={error} />
	{/if}

	<!-- Source tabs -->
	<Tabs value={source} onValueChange={(v) => (source = v as Source)}>
		<TabsList
			class="mb-4 h-auto w-full justify-start gap-1 rounded-none border-b border-border bg-transparent p-0 pb-3"
		>
			<TabsTrigger
				value="file"
				class="flex-none rounded-lg px-3 py-2 text-sm font-normal text-muted-foreground transition-colors hover:bg-muted hover:text-foreground data-active:bg-primary/10 data-active:font-medium data-active:text-primary data-active:shadow-none"
			>
				<HugeiconsIcon icon={FileUploadIcon} size={16} />
				Local file
			</TabsTrigger>
			<TabsTrigger
				value="url"
				class="flex-none rounded-lg px-3 py-2 text-sm font-normal text-muted-foreground transition-colors hover:bg-muted hover:text-foreground data-active:bg-primary/10 data-active:font-medium data-active:text-primary data-active:shadow-none"
			>
				<HugeiconsIcon icon={Link01Icon} size={16} />
				Remote URL
			</TabsTrigger>
		</TabsList>

		<TabsContent value="file">
			<div class="space-y-2">
				<Label for="artifact-import-file">File</Label>
				<Input
					id="artifact-import-file"
					type="file"
					bind:files
					accept={mode === 'import'
						? '.json,.yaml,.yml,.graphql,.graphqls,.gql,.proto'
						: '.json,.yaml,.yml'}
				/>
			</div>
		</TabsContent>

		<TabsContent value="url" class="space-y-4">
			<div class="space-y-2">
				<Label for="artifact-import-url">URL</Label>
				<Input id="artifact-import-url" bind:value={url} placeholder="https://…" autocomplete="off" />
			</div>
			<div class="space-y-2">
				<Label for="artifact-import-secret">Secret (optional)</Label>
				<ArtifactSecretSelect id="artifact-import-secret" bind:value={secretName} />
				<p class="text-muted-foreground text-xs">
					An <code class="text-xs">ARTIFACT</code> secret used by the control plane to fetch the URL.
				</p>
			</div>
		</TabsContent>
	</Tabs>

	{#if mode === 'import'}
		<div class="mt-8 space-y-2">
			<div class="grid grid-cols-2 gap-3">
				<div class="space-y-2">
					<Label for="artifact-import-service-name">Service name override</Label>
					<Input
						id="artifact-import-service-name"
						bind:value={serviceName}
						placeholder="Required for GraphQL"
						autocomplete="off"
					/>
				</div>
				<div class="space-y-2">
					<Label for="artifact-import-service-version">Service version override</Label>
					<Input
						id="artifact-import-service-version"
						bind:value={serviceVersion}
						placeholder="Required for GraphQL"
						autocomplete="off"
					/>
				</div>
			</div>
			<p class="text-muted-foreground text-xs">
				Service name and version are extracted from the specification by default; fill these fields
				to override them. They are <strong>mandatory for GraphQL</strong> specifications.
			</p>
		</div>
	{/if}
</div>

