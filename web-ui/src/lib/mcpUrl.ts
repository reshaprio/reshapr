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
