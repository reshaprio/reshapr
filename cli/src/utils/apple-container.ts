/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { execFileSync } from 'node:child_process';

import { Logger } from './logger.js';

/*
 * apple/container (https://github.com/apple/container) has no Compose, so the
 * all-in-one stack is started with plain `container run` calls. Containers
 * are wired by IP because name resolution between containers needs a sudo
 * host resolver (`container system dns create`).
 */

const POSTGRES = 'reshapr-postgres';
const CONTROL_PLANE = 'reshapr-control-plane';
const GATEWAY = 'reshapr-gateway-01';
const WEB_UI = 'reshapr-web-ui';
export const APPLE_CONTAINER_NAMES = [WEB_UI, GATEWAY, CONTROL_PLANE, POSTGRES];

const REGISTRY = 'registry.reshapr.io/reshapr';
const CTRL_TOKEN = 'my-super-secret-token-xyz';

export interface AppleContainerOptions {
  release: string;
  ui: boolean;
  /** Admin API key of the web UI, as found in the UI addon compose file. */
  uiAdminApiKey?: string;
}

function container(args: string[]): string {
  return execFileSync('container', args, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function waitFor(description: string, timeoutSeconds: number, check: () => boolean | Promise<boolean>): Promise<void> {
  for (let i = 0; i < timeoutSeconds; i++) {
    try {
      if (await check()) {
        return;
      }
    } catch {
      // not ready yet
    }
    await sleep(1000);
  }
  throw new Error(`Timeout waiting for ${description}`);
}

/** Parse the `container ls` table: ID IMAGE OS ARCH STATE IP ... (the JSON output has no IP). */
export function parseContainerList(output: string): { id: string; state: string; ip: string }[] {
  return output.split('\n').slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(cols => cols.length >= 6)
    .map(cols => ({ id: cols[0], state: cols[4], ip: cols[5].replace(/\/.*$/, '') }));
}

function ipOf(name: string): string {
  const found = parseContainerList(container(['ls', '-a'])).find(c => c.id === name);
  if (!found || !found.ip || found.ip === '-') {
    throw new Error(`Cannot find an IP for container '${name}'`);
  }
  return found.ip;
}

function existingContainers(): string[] {
  const ids = parseContainerList(container(['ls', '-a'])).map(c => c.id);
  return APPLE_CONTAINER_NAMES.filter(name => ids.includes(name));
}

async function httpOk(url: string): Promise<boolean> {
  const response = await fetch(url, { signal: AbortSignal.timeout(2000) });
  return response.ok;
}

export async function startAppleContainerStack(options: AppleContainerOptions): Promise<void> {
  const { release, ui, uiAdminApiKey } = options;

  try {
    container(['system', 'status']);
  } catch {
    throw new Error('The container system is not running. Start it with `container system start`.');
  }
  const existing = existingContainers();
  if (existing.length > 0) {
    throw new Error(`Containers already exist: ${existing.join(', ')}. Run \`reshapr stop\` first.`);
  }

  Logger.info(`Starting ${POSTGRES}...`);
  container(['run', '-d', '--name', POSTGRES,
    '-e', 'POSTGRES_USER=reshapr', '-e', 'POSTGRES_PASSWORD=reshapr', '-e', 'POSTGRES_DB=reshapr',
    'postgres:17-alpine']);
  await waitFor('postgres', 90, () => {
    container(['exec', POSTGRES, 'pg_isready', '-U', 'reshapr', '-q']);
    return true;
  });

  Logger.info(`Starting ${CONTROL_PLANE}...`);
  container(['run', '-d', '--name', CONTROL_PLANE, '-p', '127.0.0.1:5555:5555',
    '-e', `QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://${ipOf(POSTGRES)}:5432/reshapr`,
    '-e', 'QUARKUS_DATASOURCE_USERNAME=reshapr', '-e', 'QUARKUS_DATASOURCE_PASSWORD=reshapr',
    '-e', 'RESHAPR_ADMIN_NAME=admin', '-e', 'RESHAPR_ADMIN_PASSWORD=password',
    '-e', 'RESHAPR_ADMIN_EMAIL=reshapr@example.com',
    '-e', `RESHAPR_DEFAULT_GATEWAY_TOKENS=${CTRL_TOKEN}`,
    '-e', 'RESHAPR_ENCRYPTION_KEYS_V1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=',
    '-e', 'RESHAPR_ENCRYPTION_ACTIVE_KEY_ID=v1',
    '-e', 'RESHAPR_ENCRYPTION_KEY=my-super-secret-key-32-char-long',
    `${REGISTRY}/reshapr-ctrl:${release}`]);
  await waitFor('control plane', 180, () => httpOk('http://127.0.0.1:5555/q/health/ready'));

  Logger.info(`Starting ${GATEWAY}...`);
  container(['run', '-d', '--name', GATEWAY, '-p', '127.0.0.1:7777:7777',
    '-e', `RESHAPR_CTRL_HOST=${ipOf(CONTROL_PLANE)}`, '-e', 'RESHAPR_CTRL_PORT=5555',
    '-e', `RESHAPR_CTRL_TOKEN=reshapr-${CTRL_TOKEN}`,
    `${REGISTRY}/reshapr-proxy:${release}`]);

  if (ui) {
    Logger.info(`Starting ${WEB_UI}...`);
    container(['run', '-d', '--name', WEB_UI, '-p', '127.0.0.1:3333:3333',
      '-e', `RESHAPR_CTRL_URL=http://${ipOf(CONTROL_PLANE)}:5555`,
      '-e', `RESHAPR_ADMIN_API_KEY=${uiAdminApiKey ?? ''}`,
      '-e', 'RESHAPR_WEBUI_PUBLIC_URL=http://localhost:5555',
      '-e', 'ORIGIN=http://localhost:3333', '-e', 'BODY_SIZE_LIMIT=12M',
      `${REGISTRY}/reshapr-ui:${release}`]);
    await waitFor('web ui', 60, () => httpOk('http://127.0.0.1:3333/'));
  }
}

export function stopAppleContainerStack(): void {
  for (const name of APPLE_CONTAINER_NAMES) {
    for (const verb of ['stop', 'delete']) {
      try {
        container([verb, name]);
      } catch {
        // already stopped or absent
      }
    }
  }
}

export function appleContainerStatus(): string {
  const lines = container(['ls', '-a']).split('\n');
  return [lines[0], ...lines.slice(1).filter(line => APPLE_CONTAINER_NAMES.includes(line.trim().split(/\s+/)[0]))].join('\n');
}
