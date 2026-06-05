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

import { apiClient } from '$lib/api/client.js';
import {
  buildGatewayRegisteredDetail,
  quotaUsed,
  type GatewayRegisteredDetail
} from '$lib/dashboardStatsCompute.js';

export type { GatewayRegisteredDetail, GatewayRowDetail } from '$lib/dashboardStatsCompute'
export {
	aggregateGatewaysFromActiveExpositions,
	buildGatewayRegisteredDetail,
	quotaEntry
} from '$lib/dashboardStatsCompute'

type Api = ReturnType<typeof apiClient>

export type DashboardStats = {
	/** Current tenant (JWT); not a platform-wide org list. */
	organizationId: string | null
	serviceCount: number
	/** From quotas (`gateway.count` used) when available, else active exposition gateways. */
	gatewayRegisteredCount: number
	/** Gateways on active expositions with at least one FQDN (best-effort without heartbeat API). */
	gatewayHealthyCount: number
	gatewayGroupsCount: number | null
	expositionCount: number | null
	/** Not exposed on GET /api/v1/* — always null until upstream adds an endpoint. */
	userCount: null
	/** Not exposed on GET /api/v1/* — always null until upstream adds an endpoint. */
	organizationCount: null
	gatewayRegisteredDetail: GatewayRegisteredDetail
}

async function countAllServices(c: Api): Promise<{ count: number; organizationId: string | null }> {
	const size = 100
	let page = 0
	let total = 0
	let organizationId: string | null = null
	for (;;) {
		const batch = await c.listServicesPage(page, size)
		if (!Array.isArray(batch) || batch.length === 0) break
		if (!organizationId && batch[0] && typeof batch[0] === 'object') {
			const id = (batch[0] as Record<string, unknown>).organizationId
			if (typeof id === 'string' && id) organizationId = id
		}
		total += batch.length
		if (batch.length < size) break
		page += 1
	}
	return { count: total, organizationId }
}

function pickOrganizationId(services: unknown[], gatewayGroups: unknown[]): string | null {
	for (const raw of services) {
		if (raw && typeof raw === 'object') {
			const id = (raw as Record<string, unknown>).organizationId
			if (typeof id === 'string' && id) return id
		}
	}
	for (const raw of gatewayGroups) {
		if (raw && typeof raw === 'object') {
			const id = (raw as Record<string, unknown>).organizationId
			if (typeof id === 'string' && id) return id
		}
	}
	return null
}

/** Dashboard metrics using only existing v1 REST APIs (no control-plane changes). */
export async function loadDashboardStats(): Promise<DashboardStats> {
	const c = apiClient()

	const [services, activeExpositions, quotas, gatewayGroups] = await Promise.all([
		countAllServices(c),
		c.listExpositionsActive(),
		c.getQuotas(),
		c.listGatewayGroups()
	])

	const active = Array.isArray(activeExpositions) ? activeExpositions : []
	const gatewayRegisteredDetail = buildGatewayRegisteredDetail(active, quotas)
	const fromActive = gatewayRegisteredDetail.fromActiveExpositions

	const orgId =
		services.organizationId ??
		pickOrganizationId([], Array.isArray(gatewayGroups) ? gatewayGroups : [])

	return {
		organizationId: orgId,
		serviceCount: services.count,
		gatewayRegisteredCount: gatewayRegisteredDetail.displayedCount,
		gatewayHealthyCount: fromActive.healthy,
		gatewayGroupsCount: quotaUsed(quotas, 'gateway-group.count'),
		expositionCount: quotaUsed(quotas, 'exposition.count'),
		userCount: null,
		organizationCount: null,
		gatewayRegisteredDetail
	}
}
