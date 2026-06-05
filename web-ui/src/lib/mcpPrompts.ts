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
 * Resolves MCP prompts from an MCP URL via control-plane REST
 * (artifact `RESHAPR_PROMPTS`), avoiding browser CORS on the MCP gateway.
 */

import type { McpPromptArgument, McpPromptDescriptor } from './mcpTypes.js';
import { parseMcpUrl } from './mcpUrl.js';

export type McpPromptsClient = {
  listServicesPage: (page: number, size: number) => Promise<unknown[]>
  listArtifactsByService: (serviceId: string) => Promise<unknown[]>
}

export type McpPromptArtifactYaml = {
  name: string
  content: string
}

export type McpPromptsResolution = {
  prompts: McpPromptDescriptor[]
  source: 'artifacts_prompts'
  serviceId: string
  artifactNames: string[]
  /** Full YAML content per `RESHAPR_PROMPTS` artifact. */
  artifactYamls: McpPromptArtifactYaml[]
}

type ServiceRow = {
  id?: string
  organizationId?: string
  name?: string
  version?: string
}

type ArtifactRow = {
  type?: string
  name?: string
  content?: string | null
}

async function listAllServices(client: McpPromptsClient): Promise<unknown[]> {
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

function parsePromptArguments(block: string): McpPromptArgument[] {
  const argsMarker = block.match(/^\s{4}arguments:\s*$/m)
  if (!argsMarker || argsMarker.index === undefined) return []

  const after = block.slice(argsMarker.index + argsMarker[0].length)
  const args: McpPromptArgument[] = []
  const items = after.split(/^\s{6}-\s+/m).slice(1)
  for (const item of items) {
    const name = item.match(/^\s*name:\s*(.+)$/m)?.[1]?.trim()
    if (!name) continue
    const description = item.match(/^\s*description:\s*(.+)$/m)?.[1]?.trim()
    const requiredLine = item.match(/^\s*required:\s*(true|false)\s*$/m)?.[1]
    const arg: McpPromptArgument = { name }
    if (description) arg.description = description
    if (requiredLine === 'true') arg.required = true
    args.push(arg)
  }
  return args
}

/** Parse `prompts:` block from a Reshapr Prompts YAML artifact. */
export function parseReshaprPromptsYaml(content: string): McpPromptDescriptor[] {
  if (!content || typeof content !== 'string') return []
  const marker = 'prompts:'
  const idx = content.indexOf(marker)
  if (idx === -1) return []

  let rest = content.slice(idx + marker.length).replace(/^\s*\n/, '')
  const keyRe = /^  ([a-zA-Z0-9_]+):\s*$/gm
  const keys: { name: string; endHeader: number; start: number }[] = []
  let mm: RegExpExecArray | null
  while ((mm = keyRe.exec(rest)) !== null) {
    keys.push({ name: mm[1], start: mm.index, endHeader: mm.index + mm[0].length })
  }

  const prompts: McpPromptDescriptor[] = []
  for (let i = 0; i < keys.length; i++) {
    const block = rest.slice(keys[i].endHeader, i + 1 < keys.length ? keys[i + 1].start : rest.length)
    const titleLine = block.match(/^\s{4}title:\s*(.+)$/m)
    let description = ''
    const folded = block.match(/^\s{4}description:\s*>\s*\n([\s\S]*?)(?=^\s{4}(?:arguments|title|result):)/m)
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
    const arguments_ = parsePromptArguments(block)
    const prompt: McpPromptDescriptor = {
      name: keys[i].name,
      description: description || (titleLine ? titleLine[1].trim() : undefined),
    }
    if (arguments_.length) prompt.arguments = arguments_
    prompts.push(prompt)
  }
  return prompts
}

export async function resolveMcpPromptsForService(
  serviceId: string,
  client: McpPromptsClient,
): Promise<McpPromptsResolution> {
  const artifacts = (await client.listArtifactsByService(serviceId)) as ArtifactRow[]
  const promptArtifacts = (Array.isArray(artifacts) ? artifacts : []).filter(
    (a) => a?.type === 'RESHAPR_PROMPTS' && a.content,
  )
  if (promptArtifacts.length === 0) {
    return {
      prompts: [],
      source: 'artifacts_prompts',
      serviceId,
      artifactNames: [],
      artifactYamls: [],
    }
  }

  const byName = new Map<string, McpPromptDescriptor>()
  const artifactNames: string[] = []
  const artifactYamls: McpPromptArtifactYaml[] = []
  for (const artifact of promptArtifacts) {
    const label = artifact.name || `RESHAPR_PROMPTS-${artifactYamls.length + 1}`
    if (artifact.name) artifactNames.push(artifact.name)
    if (artifact.content) {
      artifactYamls.push({ name: label, content: artifact.content })
    }
    for (const p of parseReshaprPromptsYaml(artifact.content || '')) {
      byName.set(p.name, p)
    }
  }

  return {
    prompts: [...byName.values()],
    source: 'artifacts_prompts',
    serviceId,
    artifactNames,
    artifactYamls,
  }
}

export async function resolveMcpPromptsFromUrl(
  mcpUrl: string,
  client: McpPromptsClient,
): Promise<McpPromptsResolution> {
  const { orgId, serviceName, version } = parseMcpUrl(mcpUrl)
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

  return resolveMcpPromptsForService(service.id, client)
}
