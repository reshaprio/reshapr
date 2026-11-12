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
import * as fs from 'node:fs';

import * as yaml from 'js-yaml';

import { Logger } from './logger.js';

/*
 * apple/container (https://github.com/apple/container) has no Compose. The stack described by the
 * release compose files is started with plain `container run` calls: image, environment and ports
 * are read from the compose files, so they follow the release. `depends_on` gives the start order,
 * and the healthchecks (or `pg_isready` for postgres) are replayed inside the containers.
 * Containers are wired by IP because name resolution between containers needs a sudo host resolver
 * (`container system dns create`), so service names found in environment values are replaced by IPs.
 * `restart`, `extra_hosts` and `volumes` are not supported and are ignored.
 */

export interface ComposeService {
  /** Service key in the compose file, used as host name by Compose. */
  key: string;
  containerName: string;
  image: string;
  env: [string, string][];
  ports: string[];
  dependsOn: string[];
  /** Healthcheck command, replayed inside the container. */
  healthcheck?: string[];
}

interface RawService {
  image?: string;
  container_name?: string;
  environment?: string[] | Record<string, unknown>;
  ports?: unknown[];
  depends_on?: string[] | Record<string, unknown>;
  healthcheck?: { test?: string | string[] };
}

function container(args: string[]): string {
  return execFileSync('container', args, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function waitFor(description: string, timeoutSeconds: number, check: () => boolean): Promise<void> {
  for (let i = 0; i < timeoutSeconds; i++) {
    try {
      if (check()) {
        return;
      }
    } catch {
      // not ready yet
    }
    await sleep(1000);
  }
  throw new Error(`Timeout waiting for ${description}`);
}

function toEnv(environment: RawService['environment']): [string, string][] {
  if (Array.isArray(environment)) {
    return environment.map(entry => String(entry)).filter(entry => entry.includes('=')).map(entry => {
      const index = entry.indexOf('=');
      return [entry.slice(0, index), entry.slice(index + 1)] as [string, string];
    });
  }
  return Object.entries(environment ?? {}).map(([name, value]) => [name, String(value ?? '')] as [string, string]);
}

function toHealthcheck(test: string | string[] | undefined): string[] | undefined {
  if (typeof test === 'string') {
    return ['sh', '-c', test];
  }
  if (!Array.isArray(test) || test.length < 2) {
    return undefined;
  }
  if (test[0] === 'CMD') {
    return test.slice(1);
  }
  return test[0] === 'CMD-SHELL' ? ['sh', '-c', test[1]] : undefined;
}

/** Services of the given compose files, ordered so that every service comes after its `depends_on`. */
export function parseComposeServices(composeFiles: string[]): ComposeService[] {
  const services: ComposeService[] = [];
  for (const file of composeFiles) {
    const doc = yaml.load(fs.readFileSync(file, 'utf-8')) as { services?: Record<string, RawService> } | undefined;
    for (const [key, raw] of Object.entries(doc?.services ?? {})) {
      if (!raw.image) {
        throw new Error(`Service '${key}' of ${file} has no image`);
      }
      services.push({
        key,
        containerName: raw.container_name ?? `reshapr-${key}`,
        image: raw.image,
        env: toEnv(raw.environment),
        ports: (raw.ports ?? []).map(String),
        dependsOn: Array.isArray(raw.depends_on) ? raw.depends_on : Object.keys(raw.depends_on ?? {}),
        healthcheck: toHealthcheck(raw.healthcheck?.test),
      });
    }
  }

  const ordered: ComposeService[] = [];
  const visit = (service: ComposeService, path: string[]): void => {
    if (ordered.includes(service)) {
      return;
    }
    if (path.includes(service.key)) {
      throw new Error(`Circular depends_on between services: ${[...path, service.key].join(' -> ')}`);
    }
    for (const dependency of service.dependsOn) {
      const found = services.find(s => s.key === dependency);
      if (!found) {
        throw new Error(`Service '${service.key}' depends on unknown service '${dependency}'`);
      }
      visit(found, [...path, service.key]);
    }
    ordered.push(service);
  };
  services.forEach(service => visit(service, []));
  return ordered;
}

/** Replace the names of already started services (Compose host names) by their IP in an environment value. */
export function replaceServiceNames(value: string, ips: Map<string, string>): string {
  let result = value;
  for (const [name, ip] of ips) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    result = result.replace(new RegExp(`(?<![\\w.-])${escaped}(?![\\w.-])`, 'g'), ip);
  }
  return result;
}

/** Compose `HOST:CONTAINER` port mapping, restricted to the loopback interface. */
export function toPublishArg(port: string): string {
  return port.split(':').length === 2 ? `127.0.0.1:${port}` : port;
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

function existingContainers(names: string[]): string[] {
  const ids = parseContainerList(container(['ls', '-a'])).map(c => c.id);
  return names.filter(name => ids.includes(name));
}

function readinessCheck(service: ComposeService): (() => boolean) | undefined {
  const { healthcheck, containerName } = service;
  if (healthcheck) {
    return () => {
      container(['exec', containerName, ...healthcheck]);
      return true;
    };
  }
  if (/(^|\/)postgres:/.test(service.image)) {
    const user = service.env.find(([name]) => name === 'POSTGRES_USER')?.[1] ?? 'postgres';
    return () => {
      container(['exec', containerName, 'pg_isready', '-U', user, '-q']);
      return true;
    };
  }
  return undefined;
}

export async function startAppleContainerStack(composeFiles: string[]): Promise<void> {
  const services = parseComposeServices(composeFiles);

  try {
    container(['system', 'status']);
  } catch {
    throw new Error('The container system is not running. Start it with `container system start`.');
  }
  const existing = existingContainers(services.map(s => s.containerName));
  if (existing.length > 0) {
    throw new Error(`Containers already exist: ${existing.join(', ')}. Run \`reshapr stop\` first.`);
  }

  const ips = new Map<string, string>();
  for (const service of services) {
    Logger.info(`Starting ${service.containerName}...`);
    const args = ['run', '-d', '--name', service.containerName];
    service.ports.forEach(port => args.push('-p', toPublishArg(port)));
    for (const [name, value] of service.env) {
      if (value.includes('host.docker.internal')) {
        Logger.warn(`${name} refers to host.docker.internal, which is not available on apple/container.`);
      }
      args.push('-e', `${name}=${replaceServiceNames(value, ips)}`);
    }
    args.push(service.image);
    container(args);

    const ready = readinessCheck(service);
    if (ready) {
      await waitFor(service.containerName, 180, ready);
    }
    const ip = ipOf(service.containerName);
    ips.set(service.key, ip);
    ips.set(service.containerName, ip);
  }
}

export function stopAppleContainerStack(composeFiles: string[]): void {
  for (const service of parseComposeServices(composeFiles).reverse()) {
    for (const verb of ['stop', 'delete']) {
      try {
        container([verb, service.containerName]);
      } catch {
        // already stopped or absent
      }
    }
  }
}

export function appleContainerStatus(composeFiles: string[]): string {
  const names = parseComposeServices(composeFiles).map(s => s.containerName);
  const lines = container(['ls', '-a']).split('\n');
  return [lines[0], ...lines.slice(1).filter(line => names.includes(line.trim().split(/\s+/)[0]))].join('\n');
}
