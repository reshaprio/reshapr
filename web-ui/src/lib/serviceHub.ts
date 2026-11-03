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

import type { apiClient } from '#lib/api/client.js';

export type ServiceApi = ReturnType<typeof apiClient>;

export type ServiceRecord = {
	id: string;
	name: string;
	version: string;
	type: string;
	organizationId: string | null;
	operationsCount: number;
};


function asRecord(raw: unknown): Record<string, unknown> | null {
	return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : null;
}

export function parseServiceRecord(raw: unknown): ServiceRecord | null {
	const o = asRecord(raw);
	if (!o || typeof o.id !== 'string') return null;
	const ops = o.operations;
	const operationsCount = Array.isArray(ops) ? ops.length : 0;
	return {
		id: o.id,
		name: typeof o.name === 'string' ? o.name : '—',
		version: typeof o.version === 'string' ? o.version : '—',
		type: o.type != null ? String(o.type) : '—',
		organizationId: typeof o.organizationId === 'string' ? o.organizationId : null,
		operationsCount
	};
}

export function expositionBelongsToService(raw: unknown, serviceId: string): boolean {
	const o = asRecord(raw);
	const svc = asRecord(o?.service);
	return svc?.id === serviceId;
}

export function planBelongsToService(raw: unknown, serviceId: string): boolean {
	const o = asRecord(raw);
	return o?.serviceId === serviceId;
}

