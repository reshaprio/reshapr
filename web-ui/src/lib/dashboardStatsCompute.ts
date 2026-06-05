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

export type GatewayRowDetail = {
	key: string
	id?: string
	name?: string
	hasFqdn: boolean
	/** Exposition id or name where this gateway appears (active list). */
	onActiveExpositions: string[]
}

export type GatewayRegisteredDetail = {
	displayedCount: number
	/** How the card value was chosen. */
	source: 'quota_only' | 'active_expositions_only' | 'max_quota_and_active'
	quota: { metric: string; used: number; limit: number; remaining: number } | null
	fromActiveExpositions: {
		registered: number
		healthy: number
		gateways: GatewayRowDetail[]
	}
}

type GatewayRow = { id?: string; name?: string; fqdns?: unknown[] }

function expositionLabel(expo: Record<string, unknown>): string {
	if (typeof expo.id === 'string' && expo.id) return expo.id
	if (typeof expo.name === 'string' && expo.name) return expo.name
	return '(exposition)'
}

/** Dedupe gateways by `id`, else `name`, across GET /api/v1/expositions/active. */
export function aggregateGatewaysFromActiveExpositions(active: unknown[]): {
	registered: number
	healthy: number
	gateways: GatewayRowDetail[]
} {
	const byKey = new Map<string, GatewayRowDetail>()
	for (const expo of active) {
		if (!expo || typeof expo !== 'object') continue
		const expoObj = expo as Record<string, unknown>
		const expoId = expositionLabel(expoObj)
		const gws = expoObj.gateways
		if (!Array.isArray(gws)) continue
		for (const g of gws) {
			if (!g || typeof g !== 'object') continue
			const row = g as GatewayRow
			const key =
				typeof row.id === 'string' && row.id
					? row.id
					: typeof row.name === 'string' && row.name
						? row.name
						: null
			if (!key) continue
			const fqdns = Array.isArray(row.fqdns) ? row.fqdns : []
			const hasFqdn = fqdns.some((f) => typeof f === 'string' && f.trim().length > 0)
			const existing = byKey.get(key)
			if (existing) {
				existing.hasFqdn = existing.hasFqdn || hasFqdn
				if (!existing.onActiveExpositions.includes(expoId)) {
					existing.onActiveExpositions.push(expoId)
				}
			} else {
				byKey.set(key, {
					key,
					id: typeof row.id === 'string' ? row.id : undefined,
					name: typeof row.name === 'string' ? row.name : undefined,
					hasFqdn,
					onActiveExpositions: [expoId]
				})
			}
		}
	}
	const gateways = [...byKey.values()].sort((a, b) => a.key.localeCompare(b.key))
	let healthy = 0
	for (const g of gateways) {
		if (g.hasFqdn) healthy += 1
	}
	return { registered: gateways.length, healthy, gateways }
}

export function quotaEntry(
	quotas: unknown,
	metric: string
): { used: number; limit: number; remaining: number } | null {
	if (!Array.isArray(quotas)) return null
	for (const q of quotas) {
		if (!q || typeof q !== 'object') continue
		const o = q as Record<string, unknown>
		if (o.metric !== metric) continue
		const limit = Number(o.limit)
		const remaining = Number(o.remaining)
		if (!Number.isFinite(limit) || !Number.isFinite(remaining)) return null
		return { used: Math.max(0, limit - remaining), limit, remaining }
	}
	return null
}

export function quotaUsed(quotas: unknown, metric: string): number | null {
	return quotaEntry(quotas, metric)?.used ?? null
}

export function buildGatewayRegisteredDetail(
	activeExpositions: unknown[],
	quotas: unknown
): GatewayRegisteredDetail {
	const fromActive = aggregateGatewaysFromActiveExpositions(
		Array.isArray(activeExpositions) ? activeExpositions : []
	)
	const quota = quotaEntry(quotas, 'gateway.count')
	const fromQuota = quota?.used ?? null

	let displayedCount: number
	let source: GatewayRegisteredDetail['source']
	if (fromQuota == null) {
		displayedCount = fromActive.registered
		source = 'active_expositions_only'
	} else {
		displayedCount = Math.max(fromQuota, fromActive.registered)
		if (displayedCount === fromQuota && fromQuota > fromActive.registered) {
			source = 'quota_only'
		} else if (displayedCount === fromActive.registered && fromActive.registered > fromQuota) {
			source = 'active_expositions_only'
		} else {
			source = 'max_quota_and_active'
		}
	}

	return {
		displayedCount,
		source,
		quota: quota ? { metric: 'gateway.count', ...quota } : null,
		fromActiveExpositions: fromActive
	}
}
