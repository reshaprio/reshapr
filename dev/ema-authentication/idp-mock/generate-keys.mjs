#!/usr/bin/env node
/*
 * Generates the mocked Enterprise IdP signing material:
 *   - idp-mock/keys/private.pem  (RSA 2048, PKCS#8) used by 01-generate-idjag.sh
 *   - idp-mock/www/jwks.json     served by the ema-idp-mock nginx container (Keycloak fetches it)
 *
 * Re-running the script regenerates a fresh key pair (Keycloak caches JWKS, restart it afterwards).
 */
import { generateKeyPairSync, createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const keysDir = join(here, 'keys');
const wwwDir = join(here, 'www');
mkdirSync(keysDir, { recursive: true });
mkdirSync(wwwDir, { recursive: true });

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });

const privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' });
writeFileSync(join(keysDir, 'private.pem'), privatePem, { mode: 0o600 });

const jwk = publicKey.export({ format: 'jwk' });
// Stable kid derived from the modulus so the ID-JAG header can reference it.
const kid = createHash('sha256').update(jwk.n).digest('base64url').slice(0, 16);
const jwks = { keys: [{ ...jwk, kid, use: 'sig', alg: 'RS256' }] };
writeFileSync(join(wwwDir, 'jwks.json'), JSON.stringify(jwks, null, 2));
writeFileSync(join(keysDir, 'kid'), kid);

// Minimal OpenID discovery document: not strictly required by Keycloak (jwksUrl is set explicitly)
// but handy for humans and for clients probing the mock.
const issuer = process.env.EMA_IDP_ISSUER ?? 'http://localhost:8091';
const discovery = {
  issuer,
  jwks_uri: `${issuer}/jwks.json`,
  token_endpoint: `${issuer}/token`,
  authorization_endpoint: `${issuer}/authorize`,
  grant_types_supported: ['urn:ietf:params:oauth:grant-type:token-exchange'],
  id_token_signing_alg_values_supported: ['RS256'],
  note: 'Mocked enterprise IdP for the Reshapr EMA sandbox. Only jwks.json is functional; ID-JAGs are forged offline by scripts/01-generate-idjag.sh.'
};
mkdirSync(join(wwwDir, '.well-known'), { recursive: true });
writeFileSync(join(wwwDir, '.well-known', 'openid-configuration'), JSON.stringify(discovery, null, 2));

console.log(`Generated RSA key pair (kid=${kid})`);
console.log(`  private key : ${join(keysDir, 'private.pem')}`);
console.log(`  JWKS        : ${join(wwwDir, 'jwks.json')}`);
