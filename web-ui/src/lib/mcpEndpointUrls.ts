/**
 * Construction des URLs MCP à partir des DTO expositions actives du control-plane,
 * alignée sur la CLI Reshapr : {@link https://github.com/reshaprio/reshapr/blob/main/cli/src/utils/format.ts formatEndpoint}
 * et la liste {@link https://github.com/reshaprio/reshapr/blob/main/cli/src/commands/expo.ts expo list} (expositions actives).
 *
 * Aucune lecture BDD ni CLI : uniquement les réponses JSON des routes `/api/v1/expositions/...`.
 */

/** Même encodage que `encodeUrl` dans format.ts (espaces → '+'). */
export function encodeMcpPathSegment(value: string): string {
  return value.replace(/\s/g, '+')
}

/**
 * Équivalent à `formatEndpoint` + normalisation en URL HTTP absolue pour le navigateur
 * (les FQDN API sont souvent `hôte:port` sans schéma, comme dans la sortie CLI).
 */
export function buildAbsoluteMcpUrl(
  fqdn: string,
  organizationId: string,
  serviceName: string,
  serviceVersion: string,
): string {
  const relPath = `mcp/${organizationId}/${encodeMcpPathSegment(serviceName)}/${encodeMcpPathSegment(serviceVersion)}`
  const raw = fqdn.trim()
  if (!raw) throw new Error('FQDN vide')
  if (/^https?:\/\//i.test(raw)) {
    const base = raw.replace(/\/+$/, '')
    return new URL(relPath, `${base}/`).href
  }
  return new URL(relPath, `http://${raw.replace(/^\/+/, '')}/`).href
}

export type McpUrlListItem = {
  url: string
  expositionId: string
  organizationId: string
  serviceId: string
  serviceName: string
  serviceVersion: string
  fqdn: string
  gatewayName?: string
}

type GatewayRow = { name?: string; fqdns?: unknown[] }

type ActiveExpositionLike = {
  id?: string
  organizationId?: string
  service?: { id?: string; name?: string; version?: string }
  gateways?: GatewayRow[]
}

function rowsFromActivePayload(payload: unknown): ActiveExpositionLike[] {
  if (Array.isArray(payload)) return payload as ActiveExpositionLike[]
  if (payload && typeof payload === 'object') return [payload as ActiveExpositionLike]
  return []
}

/** À partir d’objets au même format que `GET /api/v1/expositions/active` (liste ou détail actif). */
export function collectMcpUrlsFromActiveExpositions(payload: unknown): McpUrlListItem[] {
  const rows = rowsFromActivePayload(payload)
  const out: McpUrlListItem[] = []
  for (const row of rows) {
    const expositionId = String(row.id || '')
    const organizationId = String(row.organizationId || '')
    const svc = row.service
    const serviceId = String(svc?.id || '')
    const serviceName = String(svc?.name || '')
    const serviceVersion = String(svc?.version || '')
    if (!expositionId || !organizationId || !serviceId || !serviceName) continue
    const gateways = Array.isArray(row.gateways) ? row.gateways : []
    for (const g of gateways) {
      const fqdns = Array.isArray(g?.fqdns) ? g.fqdns : []
      const gatewayName = typeof g?.name === 'string' ? g.name : undefined
      for (const fq of fqdns) {
        if (typeof fq !== 'string' || !fq.trim()) continue
        try {
          out.push({
            url: buildAbsoluteMcpUrl(fq, organizationId, serviceName, serviceVersion),
            expositionId,
            organizationId,
            serviceId,
            serviceName,
            serviceVersion,
            fqdn: fq.trim(),
            gatewayName,
          })
        } catch {
          // FQDN invalide : on ignore cette entrée
        }
      }
    }
  }
  return out
}

export type ListMcpUrlsDeps = {
  listExpositionsActive: () => Promise<unknown[]>
  listExpositionsAll: () => Promise<unknown[]>
  getActiveExpositionOrNull: (id: string) => Promise<unknown | null>
}

/**
 * - `active` : un seul appel `GET /api/v1/expositions/active` (équivalent `expo list` sans `--all`).
 * - `all` : `GET /api/v1/expositions` puis pour chaque id `GET /api/v1/expositions/active/{id}` (404 ignoré),
 *   comme quand la CLI affiche les endpoints seulement pour une exposition réellement active.
 */
function dedupeByUrl(items: McpUrlListItem[]): McpUrlListItem[] {
  const seen = new Set<string>()
  const out: McpUrlListItem[] = []
  for (const item of items) {
    if (seen.has(item.url)) continue
    seen.add(item.url)
    out.push(item)
  }
  return out
}

export async function listMcpEndpointUrls(mode: 'active' | 'all', deps: ListMcpUrlsDeps): Promise<McpUrlListItem[]> {
  if (mode === 'active') {
    const rows = await deps.listExpositionsActive()
    return dedupeByUrl(collectMcpUrlsFromActiveExpositions(Array.isArray(rows) ? rows : []))
  }

  const all = await deps.listExpositionsAll()
  const list = Array.isArray(all) ? all : []
  const merged: McpUrlListItem[] = []
  for (const raw of list) {
    if (!raw || typeof raw !== 'object' || !('id' in raw)) continue
    const id = String((raw as { id: unknown }).id || '')
    if (!id) continue
    const active = await deps.getActiveExpositionOrNull(id)
    if (active === null) continue
    merged.push(...collectMcpUrlsFromActiveExpositions(active))
  }

  return dedupeByUrl(merged)
}
