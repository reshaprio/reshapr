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

/** Parse MCP exposition URL path: /mcp/{organization}/{service}/{version} */

export function parseMcpUrl(mcpUrl: string): {
  orgId: string
  serviceName: string
  version: string
  host: string
} {
  let u: URL
  try {
    u = new URL(mcpUrl)
  } catch {
    throw new Error('Invalid MCP URL')
  }
  const parts = u.pathname.split('/').filter(Boolean)
  if (parts.length < 4 || String(parts[0]).toLowerCase() !== 'mcp') {
    throw new Error('Expected MCP path: /mcp/{organization}/{service}/{version}')
  }
  const orgId = decodeURIComponent(parts[1].replace(/\+/g, '%20'))
  const serviceName = decodeURIComponent(parts[2].replace(/\+/g, '%20'))
  const version = decodeURIComponent(parts.slice(3).join('/').replace(/\+/g, '%20'))
  return { orgId, serviceName, version, host: u.host }
}
