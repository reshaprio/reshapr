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
import * as os from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

describe('not logged in', () => {
  let home: string;

  beforeEach(() => {
    home = fs.mkdtempSync(path.join(os.tmpdir(), 'reshapr-cli-'));
  });

  afterEach(() => {
    fs.rmSync(home, { recursive: true, force: true });
  });

  it('asks to login instead of crashing when no config file exists', () => {
    let status = 0;
    let output = '';
    try {
      execFileSync(process.execPath, ['node_modules/jiti/lib/jiti-cli.mjs', 'src/cli.ts', 'info'], {
        env: { ...process.env, HOME: home, USERPROFILE: home },
        encoding: 'utf-8',
        stdio: 'pipe',
      });
    } catch (err) {
      const failure = err as { status: number; stdout: string; stderr: string };
      status = failure.status;
      output = `${failure.stdout}${failure.stderr}`;
    }

    expect(status).toBe(1);
    expect(output).toContain('You are not logged in');
    expect(output).not.toContain('TypeError');
  });
});
