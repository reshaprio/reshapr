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
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configCommand, secretCommand } from '../src/commands';
import { ConfigUtil } from '../src/utils/config.js';
import { applyMergePatch } from '../src/utils/editor.js';
import { Logger } from '../src/utils/logger.js';

describe('applyMergePatch', () => {
  it('merges objects recursively, removes null keys, replaces arrays, and does not mutate', () => {
    const target = { a: 1, b: { c: 2, d: 3 }, e: [1, 2] };
    const result = applyMergePatch(target, '{"b":{"c":9,"d":null},"e":[3],"f":{"g":1}}');
    expect(result).toEqual({ a: 1, b: { c: 9 }, e: [3], f: { g: 1 } });
    expect(target.b.d).toBe(3);
  });

  it('rejects invalid or non-object patches', () => {
    expect(() => applyMergePatch({}, '{nope')).toThrow('Invalid JSON patch');
    expect(() => applyMergePatch({}, '[1]')).toThrow('Patch must be a JSON object');
  });
});

describe.each([
  { label: 'config', command: configCommand, path: 'configurationPlans' },
  { label: 'secret', command: secretCommand, path: 'secrets' }
])('$label update (non-interactive)', ({ command, path }) => {
  const fetchMock = vi.fn<typeof fetch>();
  const current = { id: 'id-1', organizationId: 'org-1', name: 'current', description: 'old', extra: { k: 'v', drop: 1 } };
  let tmpDir: string;

  const putBody = () => {
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT');
    expect(put).toBeDefined();
    expect(String(put![0])).toBe(`https://server.example/api/v1/${path}/id-1`);
    return JSON.parse(put![1]!.body as string);
  };

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'reshapr-update-'));
    ConfigUtil.config = { username: 'user', server: 'https://server.example', token: 't' };
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (_url, init) =>
        new Response(init?.method === 'PUT' ? '{}' : JSON.stringify(current), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(Logger, 'info').mockImplementation(() => {});
    vi.spyOn(Logger, 'success').mockImplementation(() => {});
    vi.spyOn(Logger, 'error').mockImplementation(() => {});
    vi.spyOn(process, 'exit').mockImplementation(((code?: number) => { throw new Error(`exit ${code}`); }) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('applies --patch on the current definition', async () => {
    await command.parseAsync(['update', 'id-1', '--patch', '{"description":"new","extra":{"drop":null}}'], { from: 'user' });
    expect(putBody()).toEqual({ ...current, description: 'new', extra: { k: 'v' } });
  });

  it('sends --data as the full definition, forcing id and organizationId', async () => {
    await command.parseAsync(['update', 'id-1', '--data', '{"id":"other","organizationId":"evil","name":"replaced"}'], { from: 'user' });
    expect(putBody()).toEqual({ id: 'id-1', organizationId: 'org-1', name: 'replaced' });
  });

  it('reads the full definition from --file', async () => {
    const file = join(tmpDir, 'def.json');
    writeFileSync(file, JSON.stringify({ name: 'from-file' }));
    await command.parseAsync(['update', 'id-1', '--file', file], { from: 'user' });
    expect(putBody()).toEqual({ id: 'id-1', organizationId: 'org-1', name: 'from-file' });
  });

  it('rejects --patch combined with --data and does not call PUT', async () => {
    await expect(command.parseAsync(['update', 'id-1', '--patch', '{}', '--data', '{}'], { from: 'user' })).rejects.toThrow('exit 1');
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
  });
});
