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

/**
 * Resolves MCP custom tools from an endpoint URL and control-plane REST APIs
 * (artifacts `RESHAPR_CUSTOM_TOOLS` and service operations, filtered by exposition).
 */

export type McpCustomToolsClient = {
  listServicesPage: (page: number, size: number) => Promise<unknown[]>
  listExpositionsActive: () => Promise<unknown[]>
  listExpositionsAll: () => Promise<unknown[]>
  getExposition: (id: string) => Promise<unknown>
  listArtifactsByService: (serviceId: string) => Promise<unknown[]>
  getService: (id: string) => Promise<unknown>
}

export type McpCustomToolRow = {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

export type McpCustomToolsResolution = {
  tools: McpCustomToolRow[]
  source: 'artifacts_custom_tools' | 'services_operations'
  expoId: string
  serviceId: string
  /** Full `RESHAPR_CUSTOM_TOOLS` artifact YAML when tools come from that artifact. */
  artifactYaml?: string
}

import { parseMcpUrl } from './mcpUrl'

export { parseMcpUrl } from './mcpUrl'

function parseInputSchemaFromYamlBlock(block: string): { type: string; properties: Record<string, unknown> } {
  const m = block.match(/^    input:\s*\n([\s\S]+)/m)
  if (!m) return { type: 'object', properties: {} }
  const body = m[1]
  const properties: Record<string, Record<string, unknown>> = {}
  let cur: string | null = null
  for (const line of body.split('\n')) {
    const prop = line.match(/^        ([a-zA-Z0-9_]+):\s*$/)
    if (prop) {
      cur = prop[1]
      properties[cur] = {}
      continue
    }
    if (!cur) continue
    const typ = line.match(/^          type:\s*(.+)$/)
    if (typ) properties[cur].type = typ[1].trim()
    const desc = line.match(/^          description:\s*(.+)$/)
    if (desc) properties[cur].description = desc[1].trim()
    const def = line.match(/^          default:\s*(.+)$/)
    if (def) {
      const v = def[1].trim()
      properties[cur].default = /^\d+$/.test(v) ? Number(v) : v
    }
  }
  return { type: 'object', properties }
}

type ParsedYamlTool = {
  name: string
  tool: string
  description: string
  inputSchema: { type: string; properties: Record<string, unknown> }
}

function parseReshaprCustomToolsYaml(content: string): ParsedYamlTool[] {
  if (!content || typeof content !== 'string') return []
  const marker = 'customTools:'
  const idx = content.indexOf(marker)
  if (idx === -1) return []
  let rest = content.slice(idx + marker.length)
  rest = rest.replace(/^\s*\n/, '')
  const tools: ParsedYamlTool[] = []
  const keyRe = /^  ([a-zA-Z0-9_]+):\s*$/gm
  const keys: { name: string; start: number; endHeader: number }[] = []
  let mm: RegExpExecArray | null
  while ((mm = keyRe.exec(rest)) !== null) {
    keys.push({ name: mm[1], start: mm.index, endHeader: mm.index + mm[0].length })
  }
  for (let i = 0; i < keys.length; i++) {
    const block = rest.slice(keys[i].endHeader, i + 1 < keys.length ? keys[i + 1].start : rest.length)
    const toolLine = block.match(/^\s{4}tool:\s*(.+)$/m)
    const titleLine = block.match(/^\s{4}title:\s*(.+)$/m)
    let description = ''
    const folded = block.match(/^\s{4}description:\s*>\s*\n([\s\S]*?)(?=^\s{4}(?:input|tool|title):)/m)
    if (folded) {
      description = folded[1]
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean)
        .join(' ')
        .trim()
    } else {
      const inlineD = block.match(/^\s{4}description:\s*(.+)$/m)
      if (inlineD) description = inlineD[1].trim()
    }
    const inputSchema = parseInputSchemaFromYamlBlock(block)
    tools.push({
      name: keys[i].name,
      tool: toolLine ? toolLine[1].trim() : '',
      description: description || (titleLine ? titleLine[1].trim() : ''),
      inputSchema,
    })
  }
  return tools
}

function includedOperationsList(value: unknown): string[] {
  if (value == null) return []
  if (Array.isArray(value)) return value.map(String)
  return []
}

function filterCustomToolsByIncluded(tools: ParsedYamlTool[], included: string[]): ParsedYamlTool[] {
  if (!included.length) return tools
  const set = new Set(included)
  return tools.filter((t) => t.tool && set.has(t.tool))
}

function toolNameFromOperation(operationName: string): string {
  let s = String(operationName || '').trim()
  if (!s) return 'unnamed_tool'
  s = s.replace(/[^a-zA-Z0-9]+/g, '_').replace(/_+/g, '_')
  if (/^[0-9]/.test(s)) s = `op_${s}`
  return s
}

function fqdnToHost(fqdn: string): string {
  const s = String(fqdn || '').trim()
  if (!s) return ''
  try {
    if (/^https?:\/\//i.test(s)) return new URL(s).host
  } catch {
    // ignore
  }
  return s.split('/')[0].trim()
}

type ExpoListRow = {
  id?: string
  createdOn?: string
  service?: { id?: string }
  gateways?: { fqdns?: unknown[] }[]
}

function expositionMatchesHost(expo: ExpoListRow, serviceId: string, mcpHost: string): boolean {
  if (!expo?.service?.id || expo.service.id !== serviceId) return false
  const gateways = Array.isArray(expo.gateways) ? expo.gateways : []
  for (const g of gateways) {
    const fqdns = Array.isArray(g?.fqdns) ? g.fqdns : []
    for (const fqdn of fqdns) {
      if (typeof fqdn === 'string' && fqdnToHost(fqdn) === mcpHost) return true
    }
  }
  return false
}

function pickNewestExposition(expos: ExpoListRow[]): ExpoListRow | null {
  if (!Array.isArray(expos) || expos.length === 0) return null
  const sorted = [...expos].sort((a, b) =>
    String(b.createdOn || '').localeCompare(String(a.createdOn || '')),
  )
  return sorted[0] ?? null
}

async function listAllServices(client: McpCustomToolsClient): Promise<unknown[]> {
  const size = 100
  let page = 0
  const out: unknown[] = []
  for (;;) {
    const batch = await client.listServicesPage(page, size)
    if (!Array.isArray(batch) || batch.length === 0) break
    out.push(...batch)
    if (batch.length < size) break
    page += 1
  }
  return out
}

async function resolveExpositionForMcp(
  client: McpCustomToolsClient,
  serviceId: string,
  mcpHost: string,
): Promise<unknown> {
  const active = (await client.listExpositionsActive()) as ExpoListRow[]
  const activeList = Array.isArray(active) ? active : []
  const fromActive = pickNewestExposition(
    activeList.filter((e) => expositionMatchesHost(e, serviceId, mcpHost)),
  )
  if (fromActive?.id) {
    return client.getExposition(fromActive.id)
  }
  const all = (await client.listExpositionsAll()) as ExpoListRow[]
  const allList = Array.isArray(all) ? all : []
  const fromAll = pickNewestExposition(
    allList.filter((e) => expositionMatchesHost(e, serviceId, mcpHost)),
  )
  if (!fromAll?.id) return null
  return client.getExposition(fromAll.id)
}

type ServiceRow = {
  id?: string
  organizationId?: string
  name?: string
  version?: string
}

type ArtifactRow = {
  type?: string
  content?: string | null
}

type ExpoDetail = {
  id?: string
  configurationPlan?: { includedOperations?: unknown }
}

type ServiceView = {
  operations?: { name?: string }[]
}

async function resolveExpositionForService(
  client: McpCustomToolsClient,
  serviceId: string,
  mcpHost?: string,
): Promise<ExpoDetail | null> {
  if (mcpHost) {
    const raw = await resolveExpositionForMcp(client, serviceId, mcpHost)
    return raw as ExpoDetail | null
  }
  const active = (await client.listExpositionsActive()) as ExpoListRow[]
  const activeList = Array.isArray(active) ? active : []
  const fromActive = pickNewestExposition(
    activeList.filter((e) => e?.service?.id === serviceId),
  )
  if (fromActive?.id) {
    return (await client.getExposition(fromActive.id)) as ExpoDetail
  }
  const all = (await client.listExpositionsAll()) as ExpoListRow[]
  const allList = Array.isArray(all) ? all : []
  const fromAll = pickNewestExposition(allList.filter((e) => e?.service?.id === serviceId))
  if (!fromAll?.id) return null
  return (await client.getExposition(fromAll.id)) as ExpoDetail
}

export async function resolveMcpCustomToolsForService(
  serviceId: string,
  client: McpCustomToolsClient,
  options?: { exposition?: ExpoDetail | null },
): Promise<McpCustomToolsResolution> {
  const view = (await client.getService(serviceId)) as ServiceRow & ServiceView
  if (!view?.id) {
    throw new Error(`Service not found: ${serviceId}`)
  }
  const expo =
    options && 'exposition' in options
      ? (options.exposition ?? null)
      : await resolveExpositionForService(client, serviceId)
  if (!expo?.id) {
    const artifacts = (await client.listArtifactsByService(serviceId)) as ArtifactRow[]
    const artifactList = Array.isArray(artifacts) ? artifacts : []
    const yamlArtifact = artifactList.find((a) => a && a.type === 'RESHAPR_CUSTOM_TOOLS' && a.content)
    const customParsed = parseReshaprCustomToolsYaml(yamlArtifact?.content || '')
    if (customParsed.length > 0) {
      const customOut: McpCustomToolRow[] = customParsed.map((t) => ({
        name: t.name,
        description: t.description || '',
        inputSchema: (t.inputSchema || { type: 'object', properties: {} }) as Record<string, unknown>,
      }))
      return {
        tools: customOut,
        source: 'artifacts_custom_tools',
        expoId: '',
        serviceId,
        artifactYaml: yamlArtifact?.content ?? undefined,
      }
    }
    const ops = Array.isArray(view?.operations) ? view.operations : []
    const restOut: McpCustomToolRow[] = ops.map((op) => ({
      name: toolNameFromOperation(String(op.name)),
      description: String(op.name),
      inputSchema: { type: 'object' },
    }))
    return {
      tools: restOut,
      source: 'services_operations',
      expoId: '',
      serviceId,
    }
  }
  const included = includedOperationsList(expo.configurationPlan?.includedOperations)
  const artifacts = (await client.listArtifactsByService(serviceId)) as ArtifactRow[]
  const artifactList = Array.isArray(artifacts) ? artifacts : []
  const yamlArtifact = artifactList.find((a) => a && a.type === 'RESHAPR_CUSTOM_TOOLS' && a.content)
  const customParsed = parseReshaprCustomToolsYaml(yamlArtifact?.content || '')
  const customFiltered = filterCustomToolsByIncluded(customParsed, included)
  const customOut: McpCustomToolRow[] = customFiltered.map((t) => ({
    name: t.name,
    description: t.description || '',
    inputSchema: (t.inputSchema || { type: 'object', properties: {} }) as Record<string, unknown>,
  }))
  if (customOut.length > 0) {
    return {
      tools: customOut,
      source: 'artifacts_custom_tools',
      expoId: expo.id,
      serviceId,
      artifactYaml: yamlArtifact?.content ?? undefined,
    }
  }
  const ops = Array.isArray(view?.operations) ? view.operations : []
  const includedSet = new Set(included)
  const restOps = included.length ? ops.filter((o) => o && includedSet.has(String(o.name))) : ops
  const restOut: McpCustomToolRow[] = restOps.map((op) => ({
    name: toolNameFromOperation(String(op.name)),
    description: String(op.name),
    inputSchema: { type: 'object' },
  }))
  return {
    tools: restOut,
    source: 'services_operations',
    expoId: expo.id,
    serviceId,
  }
}

export async function resolveMcpCustomToolsFromUrl(
  mcpUrl: string,
  client: McpCustomToolsClient,
): Promise<McpCustomToolsResolution> {
  const { orgId, serviceName, version, host } = parseMcpUrl(mcpUrl)
  const services = (await listAllServices(client)) as ServiceRow[]
  const service = services.find(
    (s) =>
      s &&
      s.organizationId === orgId &&
      s.name === serviceName &&
      s.version === version,
  )
  if (!service?.id) {
    throw new Error(`Service not found for ${orgId} / ${serviceName} / ${version}`)
  }
  const expoRaw = await resolveExpositionForService(client, service.id, host)
  if (!expoRaw?.id) {
    throw new Error(
      'No exposition (active or full list) has FQDNs matching the MCP URL host for this service',
    )
  }
  return resolveMcpCustomToolsForService(service.id, client, { exposition: expoRaw })
}
