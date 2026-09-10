#!/usr/bin/env node
/*
 * Forges an Identity Assertion JWT Authorization Grant (ID-JAG) as the mocked Enterprise IdP would
 * issue it after a successful Token Exchange (RFC 8693) — see MCP EMA spec §4.3 and
 * draft-ietf-oauth-identity-assertion-authz-grant §3.1.
 *
 * Usage: node forge-idjag.mjs [--sub U-alice-001] [--email alice@acme.example]
 *          [--aud http://localhost:8090/realms/reshapr-ema] [--resource http://localhost:7777/mcp/reshapr/ema-weather]
 *          [--client-id mcp-client] [--scope "mcp.read"] [--ttl 300] [--jti <fixed>] [--typ oauth-id-jag+jwt]
 * Prints the compact JWT on stdout.
 */
import { createSign, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => {
    if (a.startsWith('--')) acc.push([a.slice(2), arr[i + 1] ?? 'true']);
    return acc;
  }, [])
);

const issuer = args.iss ?? process.env.EMA_IDP_ISSUER ?? 'http://localhost:8091';
const now = Math.floor(Date.now() / 1000);
const ttl = parseInt(args.ttl ?? '300', 10);

const header = {
  typ: args.typ ?? 'oauth-id-jag+jwt',
  alg: 'RS256',
  kid: readFileSync(join(here, 'keys', 'kid'), 'utf8').trim()
};

const payload = {
  jti: args.jti ?? randomUUID(),
  iss: issuer,
  sub: args.sub ?? 'U-alice-001',
  email: args.email ?? 'alice@acme.example',
  aud: args.aud ?? 'http://localhost:8090/realms/reshapr-ema',
  resource: args.resource ?? 'http://localhost:7777/mcp/reshapr/ema-weather',
  client_id: args['client-id'] ?? 'mcp-client',
  iat: now,
  exp: now + ttl,
  scope: args.scope ?? 'mcp.read'
};

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const signingInput = `${b64(header)}.${b64(payload)}`;
const signer = createSign('RSA-SHA256');
signer.update(signingInput);
const signature = signer.sign(readFileSync(join(here, 'keys', 'private.pem'))).toString('base64url');

if (args.verbose) {
  console.error(JSON.stringify({ header, payload }, null, 2));
}
process.stdout.write(`${signingInput}.${signature}\n`);
