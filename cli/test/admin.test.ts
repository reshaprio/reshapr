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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAdminCommand } from '../src/commands/admin/index.js';
import {
  AdminCommandError,
  parseStringArray,
  resolveAdminConnection
} from '../src/commands/admin/utils.js';
import { parseQuotas } from '../src/commands/admin/quota.js';
import { ConfigUtil } from '../src/utils/config.js';
import { Context } from '../src/utils/context.js';
import { Logger } from '../src/utils/logger.js';

describe('admin commands', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    ConfigUtil.config = {
      username: 'user',
      server: 'https://saved.example',
      token: 'user-token'
    };
    Context.clear();
    delete process.env.RESHAPR_ADMIN_API_KEY;
    process.exitCode = undefined;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    delete process.env.RESHAPR_ADMIN_API_KEY;
    process.exitCode = undefined;
  });

  it('uses admin flags ahead of environment and saved configuration', async () => {
    process.env.RESHAPR_ADMIN_API_KEY = 'environment-key';
    fetchMock.mockResolvedValue(jsonResponse({
      username: 'jdoe',
      email: 'jdoe@example.com'
    }, 201));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'flag-key',
      '--server', 'https://flag.example/base/',
      'user', 'create', 'jdoe',
      '--email', 'jdoe@example.com',
      '--password', 'secret',
      '--firstname', 'John',
      '--lastname', 'Doe'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledWith(
      'https://flag.example/base/api/admin/users',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-reshapr-api-key': 'flag-key'
        },
        body: JSON.stringify({
          username: 'jdoe',
          email: 'jdoe@example.com',
          password: 'secret',
          firstname: 'John',
          lastname: 'Doe'
        })
      })
    );
    expect(Context.get('user')).toEqual({
      username: 'jdoe',
      email: 'jdoe@example.com'
    });
  });

  it('creates an owned organization using an encoded owner path', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ name: 'acme' }, 201));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'organization', 'create', 'acme',
      '--owner', 'owner/name',
      '--description', 'Acme organization'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/users/owner%2Fname/organization',
      'POST',
      {
        name: 'acme',
        description: 'Acme organization'
      }
    );
  });

  it('assigns validated quotas using the organization endpoint', async () => {
    const quotas = [{ metric: 'gateway.count', enabled: true, limit: 3 }];
    fetchMock.mockResolvedValue(jsonResponse(quotas));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'quota', 'assign', 'acme/dev',
      '--quotas', JSON.stringify(quotas)
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/quotas/organization/acme%2Fdev',
      'POST',
      quotas
    );
    expect(Context.get('quotas')).toEqual(quotas);
  });

  it('deletes a user using the --force flag to skip the confirmation prompt', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'user', 'delete', 'j doe',
      '--force'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledWith(
      'https://saved.example/api/admin/users/j%20doe',
      expect.objectContaining({
        method: 'DELETE',
        headers: {
          'x-reshapr-api-key': 'key'
        }
      })
    );
  });

  it('deletes an organization using the --force flag to skip the confirmation prompt', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'organization', 'delete', 'ac me',
      '--force'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledWith(
      'https://saved.example/api/admin/organizations/ac%20me',
      expect.objectContaining({
        method: 'DELETE',
        headers: {
          'x-reshapr-api-key': 'key'
        }
      })
    );
  });

  it('replaces memberships with the supplied organization list', async () => {
    fetchMock.mockResolvedValue(jsonResponse(['acme', 'shared']));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'membership', 'set', 'jdoe',
      '--organizations', '["acme","shared"]'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/users/jdoe/memberships',
      'PUT',
      ['acme', 'shared']
    );
  });

  it('creates a service account with numeric validity days', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'sa-id', name: 'operator' }, 201));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'create', 'operator',
      '--k8s-subject', 'reshapr-system:operator',
      '--allowed-organizations', '["*"]',
      '--validity-days', '90'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/serviceAccounts',
      'POST',
      {
        name: 'operator',
        k8sSubject: 'reshapr-system:operator',
        allowedOrganizations: ['*'],
        validityDays: 90
      }
    );
  });

  it('lists service accounts', async () => {
    const serviceAccounts = [serviceAccountFixture()];
    fetchMock.mockResolvedValue(jsonResponse(serviceAccounts));
    vi.spyOn(Logger, 'log').mockImplementation(() => {});

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'list'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledWith(
      'https://saved.example/api/admin/serviceAccounts',
      expect.objectContaining({ method: 'GET', body: undefined })
    );
    expect(Context.get('serviceAccounts')).toEqual(serviceAccounts);
  });

  it('updates a service account by id, keeping omitted fields', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse([serviceAccountFixture()]))
      .mockResolvedValueOnce(jsonResponse(serviceAccountFixture({ k8sSubject: 'reshapr-system:reshapr-operator' })));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'update', 'sa-id',
      '--k8s-subject', 'reshapr-system:reshapr-operator',
      '--validity-days', '30'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/serviceAccounts/sa-id',
      'PUT',
      {
        name: 'operator',
        description: 'Operator',
        k8sSubject: 'reshapr-system:reshapr-operator',
        allowedOrganizations: ['reshapr'],
        validityDays: 30
      }
    );
  });

  it('keeps the remaining validity when updating without --validity-days', async () => {
    const validUntil = new Date(Date.now() + 10 * 24 * 60 * 60 * 1000 - 60_000).toISOString();
    fetchMock
      .mockResolvedValueOnce(jsonResponse([serviceAccountFixture({ validUntil })]))
      .mockResolvedValueOnce(jsonResponse(serviceAccountFixture()));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'update', 'sa-id',
      '--allowed-organizations', '["*"]'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/serviceAccounts/sa-id',
      'PUT',
      {
        name: 'operator',
        description: 'Operator',
        k8sSubject: 'reshapr-system:operator',
        allowedOrganizations: ['*'],
        validityDays: 10
      }
    );
  });

  it('refuses to update an expired service account without --validity-days', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([
      serviceAccountFixture({ validUntil: new Date(Date.now() - 1000).toISOString() })
    ]));
    const errorSpy = vi.spyOn(Logger, 'error').mockImplementation(() => {});

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'update', 'sa-id',
      '--k8s-subject', 'reshapr-system:other'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(process.exitCode).toBe(1);
    expect(errorSpy).toHaveBeenCalledWith(
      "Service account 'operator' has expired. Use --validity-days to renew it."
    );
  });

  it('deletes a service account by id using the --force flag', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'delete', 'sa-id',
      '--force'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      'https://saved.example/api/admin/serviceAccounts/sa-id',
      expect.objectContaining({ method: 'DELETE' })
    );
  });

  it('reports an unknown service account on update', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]));
    const errorSpy = vi.spyOn(Logger, 'error').mockImplementation(() => {});

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'update', 'missing',
      '--validity-days', '30'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(process.exitCode).toBe(1);
    expect(errorSpy).toHaveBeenCalledWith("Service account 'missing' not found.");
  });

  function serviceAccountFixture(overrides: Record<string, unknown> = {}) {
    return {
      id: 'sa-id',
      name: 'operator',
      description: 'Operator',
      k8sSubject: 'reshapr-system:operator',
      allowedOrganizations: ['reshapr'],
      validUntil: new Date(Date.now() + 90 * 24 * 60 * 60 * 1000).toISOString(),
      ...overrides
    };
  }

  it('reports API errors without treating them as successful output', async () => {
    fetchMock.mockResolvedValue(new Response('User already exists', {
      status: 409,
      statusText: 'Conflict'
    }));
    const errorSpy = vi.spyOn(Logger, 'error').mockImplementation(() => {});

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'user', 'create', 'jdoe',
      '--email', 'jdoe@example.com'
    ], { from: 'user' });

    expect(process.exitCode).toBe(1);
    expect(errorSpy).toHaveBeenCalledWith(
      'Admin request failed (409 Conflict): User already exists'
    );
    expect(Context.isEmpty()).toBe(true);
  });

  it('reads the active encryption key id from the status endpoint', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ activeKid: 'v2' }));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'encryption', 'status'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/encryption/status',
      'POST',
      {}
    );
    expect(Context.get('encryption')).toEqual({ activeKid: 'v2' });
  });

  it('rotates encryption keys when confirmed with --yes', async () => {
    const report = { secretsRotated: 4, configurationPlansRotated: 2 };
    fetchMock.mockResolvedValue(jsonResponse(report));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'encryption', 'rotate', '--yes'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/encryption/rotate',
      'POST',
      {}
    );
    expect(Context.get('rotation')).toEqual(report);
  });

  it('does not rotate encryption keys without the --yes confirmation', async () => {
    fetchMock.mockClear();
    const warnSpy = vi.spyOn(Logger, 'warn').mockImplementation(() => {});

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'encryption', 'rotate'
    ], { from: 'user' });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalled();
    expect(Context.isEmpty()).toBe(true);
  });

  function expectRequest(url: string, method: string, body: unknown): void {
    expect(fetchMock).toHaveBeenCalledWith(
      url,
      expect.objectContaining({
        method,
        body: JSON.stringify(body)
      })
    );
  }
});

describe('admin input validation', () => {
  afterEach(() => {
    delete process.env.RESHAPR_ADMIN_API_KEY;
  });

  it('requires an admin API key', () => {
    ConfigUtil.config = {
      username: 'user',
      server: 'https://saved.example',
      token: 'user-token'
    };

    expect(() => resolveAdminConnection({})).toThrowError(
      new AdminCommandError(
        'An admin API key is required. Use --admin-api-key or set RESHAPR_ADMIN_API_KEY.'
      )
    );
  });

  it('uses the environment key and saved server when flags are absent', () => {
    process.env.RESHAPR_ADMIN_API_KEY = 'environment-key';
    ConfigUtil.config = {
      username: 'user',
      server: 'https://saved.example/',
      token: 'user-token'
    };

    expect(resolveAdminConnection({})).toEqual({
      adminApiKey: 'environment-key',
      server: 'https://saved.example'
    });
  });

  it('requires a server from a flag or saved login configuration', () => {
    process.env.RESHAPR_ADMIN_API_KEY = 'environment-key';
    ConfigUtil.config = {
      username: '',
      server: '',
      token: ''
    };

    expect(() => resolveAdminConnection({})).toThrow(
      'A control-plane server is required. Use --server or login to save a server.'
    );
  });

  it('rejects malformed structured options', () => {
    expect(() => parseStringArray('["acme",3]', '--organizations'))
      .toThrow('--organizations must be a JSON array of strings.');
    expect(() => parseQuotas('[{"metric":"gateway.count","enabled":"yes","limit":3}]'))
      .toThrow('--quotas must be a JSON array of objects');
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json'
    }
  });
}
