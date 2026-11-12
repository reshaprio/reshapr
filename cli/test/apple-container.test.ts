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

import { describe, expect, it } from 'vitest';
import { parseComposeServices, parseContainerList, replaceServiceNames, toPublishArg } from '../src/utils/apple-container.js';

function writeCompose(content: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'compose-'));
  const file = path.join(dir, 'compose.yml');
  fs.writeFileSync(file, content);
  return file;
}

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

describe('compose file translation', () => {
  const compose = `
services:
  gateway:
    image: registry.reshapr.io/reshapr/reshapr-proxy:1.0.0
    container_name: reshapr-gateway-01
    depends_on:
      control-plane:
        condition: service_healthy
    ports: ["7777:7777"]
    environment:
      - RESHAPR_CTRL_HOST=control-plane
      - QUARKUS_OTEL_SDK_DISABLED=true
  control-plane:
    image: registry.reshapr.io/reshapr/reshapr-ctrl:1.0.0
    depends_on: [postgres]
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:5555/q/health/ready" ]
    environment:
      RESHAPR_ADMIN_NAME: admin
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/reshapr
  postgres:
    image: library/postgres:17-alpine
`;

  it('orders services by depends_on and reads image, env (list or map), ports and healthcheck', () => {
    const services = parseComposeServices([writeCompose(compose)]);

    expect(services.map(s => s.key)).toEqual(['postgres', 'control-plane', 'gateway']);
    expect(services[0].containerName).toBe('reshapr-postgres');
    expect(services[1].env).toContainEqual(['RESHAPR_ADMIN_NAME', 'admin']);
    expect(services[1].healthcheck).toEqual(['curl', '-f', 'http://localhost:5555/q/health/ready']);
    expect(services[2].env).toContainEqual(['QUARKUS_OTEL_SDK_DISABLED', 'true']);
    expect(services[2].ports).toEqual(['7777:7777']);
  });

  it('rejects a dependency on an unknown service', () => {
    const file = writeCompose('services:\n  a:\n    image: x\n    depends_on: [missing]\n');
    expect(() => parseComposeServices([file])).toThrow(/unknown service 'missing'/);
  });

  it('replaces service names by IPs without touching longer words', () => {
    const ips = new Map([['postgres', '192.168.65.2'], ['control-plane', '192.168.65.3']]);

    expect(replaceServiceNames('jdbc:postgresql://postgres:5432/reshapr', ips)).toBe('jdbc:postgresql://192.168.65.2:5432/reshapr');
    expect(replaceServiceNames('control-plane', ips)).toBe('192.168.65.3');
    expect(replaceServiceNames('reshapr-postgres', ips)).toBe('reshapr-postgres');
  });

  it('restricts published ports to loopback', () => {
    expect(toPublishArg('5555:5555')).toBe('127.0.0.1:5555:5555');
    expect(toPublishArg('0.0.0.0:80:80')).toBe('0.0.0.0:80:80');
  });
});
