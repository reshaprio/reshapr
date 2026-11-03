/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { BootstrapConfiguration } from '#lib/types.js';

/** Cached bootstrap configuration (fetched once at app startup). */
let cached: BootstrapConfiguration | null = null;

/**
 * Fetch the bootstrap configuration from the SvelteKit server proxy
 * (`/api/config`), which itself forwards to the control plane.
 *
 * The result is cached for the lifetime of the browser session so
 * subsequent calls return immediately.
 */
export async function getBootstrapConfig(): Promise<BootstrapConfiguration> {
  if (cached) return cached;

  const res = await fetch('/api/config');
  if (!res.ok) {
    throw new Error(`Failed to fetch bootstrap configuration: ${res.status}`);
  }

  cached = (await res.json()) as BootstrapConfiguration;
  console.log('Bootstrap config:', cached);
  return cached;
}

/** Reset the cache (useful for testing). */
export function resetConfigCache(): void {
  cached = null;
}

