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

import { ApiError } from './errors.js';

export { ApiError } from './errors.js';

async function parseErrorBody(res: Response): Promise<string> {
  const t = await res.text();
  return t || res.statusText;
}

/**
 * REST client for control-plane v1 APIs via the SvelteKit BFF proxy.
 * Authentication is handled server-side (httpOnly cookie); no Bearer header in the browser.
 */
export function apiClient() {
  const json = async <T>(path: string, init?: RequestInit): Promise<T> => {
    const res = await fetch(path, init);
    if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
    if (res.status === 204) return undefined as T;
    const ct = res.headers.get('content-type');
    if (ct?.includes('application/json')) return res.json() as Promise<T>;
    return (await res.text()) as T;
  };

  const empty = async (path: string, init?: RequestInit) => {
    const res = await fetch(path, init);
    if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
  };

  return {
    listServices: () => json<unknown[]>('/api/v1/services?page=0&size=500'),
    listServicesPage: (page: number, size: number) =>
      json<unknown[]>(`/api/v1/services?page=${page}&size=${size}`),
    getService: (id: string) => json<unknown>(`/api/v1/services/${id}`),
    deleteService: (id: string) => empty(`/api/v1/services/${id}`, { method: 'DELETE' }),

    listArtifactsByService: (serviceId: string) =>
      json<unknown[]>(`/api/v1/artifacts/service/${serviceId}`),

    importArtifactFile: async (file: File, extra?: Record<string, string>) => {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('mainArtifact', 'true');
      if (extra?.serviceName) fd.append('serviceName', extra.serviceName);
      if (extra?.serviceVersion) fd.append('serviceVersion', extra.serviceVersion);
      const res = await fetch('/api/v1/artifacts', { method: 'POST', body: fd });
      if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
      return res.json();
    },

    importArtifactUrl: async (params: URLSearchParams) => {
      const res = await fetch('/api/v1/artifacts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      });
      if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
      return res.json();
    },

    attachArtifactFile: async (file: File) => {
      const fd = new FormData();
      fd.append('file', file);
      const res = await fetch('/api/v1/artifacts/attach', { method: 'POST', body: fd });
      if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
      return res.json();
    },

    attachArtifactUrl: async (url: string, secretName?: string) => {
      const p = new URLSearchParams();
      p.set('url', url);
      if (secretName) p.set('secretName', secretName);
      const res = await fetch('/api/v1/artifacts/attach', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: p.toString()
      });
      if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
      return res.json();
    },

    listConfigurationPlans: () => json<unknown[]>('/api/v1/configurationPlans'),
    getConfigurationPlan: (id: string) => json<unknown>(`/api/v1/configurationPlans/${id}`),
    createConfigurationPlan: (body: unknown) =>
      json<unknown>('/api/v1/configurationPlans', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    updateConfigurationPlan: (id: string, body: unknown) =>
      json<unknown>(`/api/v1/configurationPlans/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    deleteConfigurationPlan: (id: string) =>
      empty(`/api/v1/configurationPlans/${id}`, { method: 'DELETE' }),
    renewApiKey: (id: string) =>
      json<unknown>(`/api/v1/configurationPlans/${id}/renewApiKey`, { method: 'PUT' }),

    listExpositionsAll: () => json<unknown[]>('/api/v1/expositions'),
    listExpositionsActive: () => json<unknown[]>('/api/v1/expositions/active'),
    getExposition: (id: string) => json<unknown>(`/api/v1/expositions/${id}`),
    getActiveExposition: (id: string) => json<unknown>(`/api/v1/expositions/active/${id}`),
    getActiveExpositionOrNull: async (id: string): Promise<unknown | null> => {
      const res = await fetch(`/api/v1/expositions/active/${id}`);
      if (res.status === 404) return null;
      if (!res.ok) throw new ApiError(await parseErrorBody(res), res.status);
      return res.json() as Promise<unknown>;
    },
    createExposition: (body: { configurationPlanId: string; gatewayGroupId: string }) =>
      json<unknown>('/api/v1/expositions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    deleteExposition: (id: string) => empty(`/api/v1/expositions/${id}`, { method: 'DELETE' }),

    listSecretRefs: () => json<unknown[]>('/api/v1/secrets/refs?page=0&size=500'),
    listSecrets: (page = 0, size = 500) =>
      json<unknown[]>(`/api/v1/secrets?page=${page}&size=${size}`),
    getSecret: (id: string) => json<unknown>(`/api/v1/secrets/${id}`),
    createSecret: (body: unknown) =>
      json<unknown>('/api/v1/secrets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    updateSecret: (id: string, body: unknown) =>
      json<unknown>(`/api/v1/secrets/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    deleteSecret: (id: string) => empty(`/api/v1/secrets/${id}`, { method: 'DELETE' }),

    listGatewayGroups: () => json<unknown[]>('/api/v1/gatewayGroups'),
    createGatewayGroup: (body: unknown) =>
      json<unknown>('/api/v1/gatewayGroups', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    deleteGatewayGroup: (id: string) => empty(`/api/v1/gatewayGroups/${id}`, { method: 'DELETE' }),

    getQuotas: () => json<unknown>('/api/v1/quotas'),

    listApiTokens: () => json<unknown[]>('/api/v1/tokens/apiTokens'),
    createApiToken: (body: unknown) =>
      json<unknown>('/api/v1/tokens/apiTokens', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }),
    deleteApiToken: (tokenId: string) =>
      empty(`/api/v1/tokens/apiTokens/${tokenId}`, { method: 'DELETE' })
  };
}
