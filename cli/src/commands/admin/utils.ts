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
import { ConfigUtil } from '../../utils/config.js';

export interface AdminConnectionOptions {
  adminApiKey?: string;
  server?: string;
}

export interface AdminConnection {
  adminApiKey: string;
  server: string;
}

export class AdminCommandError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AdminCommandError';
  }
}

export function resolveAdminConnection(options: AdminConnectionOptions): AdminConnection {
  const adminApiKey = options.adminApiKey?.trim() || process.env.RESHAPR_ADMIN_API_KEY?.trim();
  if (!adminApiKey) {
    throw new AdminCommandError(
      'An admin API key is required. Use --admin-api-key or set RESHAPR_ADMIN_API_KEY.'
    );
  }

  const configuredServer = ConfigUtil.config?.server;
  const serverValue = options.server?.trim() || configuredServer?.trim();
  if (!serverValue) {
    throw new AdminCommandError(
      'A control-plane server is required. Use --server or login to save a server.'
    );
  }

  let server: URL;
  try {
    server = new URL(serverValue);
  } catch {
    throw new AdminCommandError(`Invalid control-plane server URL: ${serverValue}`);
  }
  if (server.protocol !== 'http:' && server.protocol !== 'https:') {
    throw new AdminCommandError('The control-plane server URL must use http or https.');
  }

  return {
    adminApiKey,
    server: server.toString().replace(/\/$/, '')
  };
}

export async function adminRequest<T>(
  path: string,
  options: AdminConnectionOptions,
  method: 'GET' | 'POST' | 'PUT',
  body?: unknown
): Promise<T> {
  const connection = resolveAdminConnection(options);
  let response: Response;
  try {
    response = await fetch(`${connection.server}/api/admin/${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'x-reshapr-api-key': connection.adminApiKey
      },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new AdminCommandError(`Unable to reach the control-plane server: ${message}`);
  }

  const responseBody = await response.text();
  if (!response.ok) {
    const details = responseBody.trim().slice(0, 500);
    throw new AdminCommandError(
      `Admin request failed (${response.status} ${response.statusText})${details ? `: ${details}` : ''}`
    );
  }
  if (!responseBody.trim()) {
    throw new AdminCommandError('Admin request succeeded but returned an empty response.');
  }

  try {
    return JSON.parse(responseBody) as T;
  } catch {
    throw new AdminCommandError('Admin request returned invalid JSON.');
  }
}

export async function adminDelete(
  path: string,
  options: AdminConnectionOptions
): Promise<void> {
  const connection = resolveAdminConnection(options);
  let response: Response;
  try {
    response = await fetch(`${connection.server}/api/admin/${path}`, {
      method: 'DELETE',
      headers: {
        'x-reshapr-api-key': connection.adminApiKey
      }
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new AdminCommandError(`Unable to reach the control-plane server: ${message}`);
  }

  if (!response.ok) {
    const details = (await response.text()).trim().slice(0, 500);
    throw new AdminCommandError(
      `Admin request failed (${response.status} ${response.statusText})${details ? `: ${details}` : ''}`
    );
  }
}

export function parseStringArray(value: string, optionName: string): string[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new AdminCommandError(`${optionName} must be a valid JSON array.`);
  }
  if (!Array.isArray(parsed) || parsed.some(item => typeof item !== 'string')) {
    throw new AdminCommandError(`${optionName} must be a JSON array of strings.`);
  }
  return parsed;
}
