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
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveExtraComposeFiles, runCommand } from '../src/commands/run.js';

describe('reshapr run --compose-file', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('resolves extra compose files to absolute paths, in order', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'run-'));
    const first = path.join(dir, 'a.yaml');
    const second = path.join(dir, 'b.yaml');
    fs.writeFileSync(first, 'services: {}');
    fs.writeFileSync(second, 'services: {}');

    const cwd = process.cwd();
    process.chdir(dir);
    try {
      expect(resolveExtraComposeFiles(['a.yaml', second]).map((f) => fs.realpathSync(f)))
        .toEqual([fs.realpathSync(first), fs.realpathSync(second)]);
    } finally {
      process.chdir(cwd);
    }
  });

  it('exits when an extra compose file does not exist', () => {
    const exit = vi.spyOn(process, 'exit').mockImplementation((() => {
      throw new Error('exit');
    }) as never);

    expect(() => resolveExtraComposeFiles(['/does/not/exist.yaml'])).toThrow('exit');
    expect(exit).toHaveBeenCalledWith(1);
  });
});
