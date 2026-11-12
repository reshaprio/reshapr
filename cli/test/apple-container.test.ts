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
import { describe, expect, it } from 'vitest';
import { parseContainerList } from '../src/utils/apple-container.js';

describe('apple/container output parsing', () => {
  it('extracts id, state and bare IP from the `container ls` table', () => {
    const output = [
      'ID                     IMAGE                                            OS     ARCH   STATE    IP                CPUS  MEMORY   STARTED',
      'reshapr-postgres       docker.io/library/postgres:17-alpine             linux  arm64  running  192.168.65.2/24   4     1024 MB  2026-11-12T00:30:22Z',
      'reshapr-gateway-01     registry.reshapr.io/reshapr/reshapr-proxy:1.1.0  linux  arm64  stopped  -                 4     1024 MB  2026-11-12T00:30:30Z',
      '',
    ].join('\n');

    expect(parseContainerList(output)).toEqual([
      { id: 'reshapr-postgres', state: 'running', ip: '192.168.65.2' },
      { id: 'reshapr-gateway-01', state: 'stopped', ip: '-' },
    ]);
  });
});
