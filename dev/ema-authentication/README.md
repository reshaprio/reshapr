# EMA sandbox — MCP Enterprise-Managed Authorization (ID-JAG) with an external Authorization Server

Isolated playground for **Phase A (pass-through)** of `../../plan-enterpriseManagedAuthorization.md`:
Reshapr stays a pure MCP *Resource Server*; the ID-JAG flow is played between an (mocked) enterprise
IdP, a **Keycloak acting as MCP Resource Authorization Server**, and the Reshapr proxy that validates
the resulting access tokens.

> Nothing here touches Reshapr's code, `install/` stacks or dev configuration. Everything lives in
> `dev/ema-authentication/` and the only Reshapr-side effects are **runtime objects** (service / plan /
> exposition) created through the public REST API and removed by `scripts/99-cleanup.sh`.

## Topology

```
                 ┌──────────────────────────┐   1. SSO (simulated)       ┌────────────────────────┐
                 │ Enterprise IdP (mocked)  │◀───────────────────────────│                        │
                 │ ema-idp-mock :8091       │   2. token-exchange →      │      MCP Client        │
                 │ static JWKS + forge      │──────── ID-JAG ───────────▶│   (scripts 01→03)      │
                 └──────────────────────────┘                            │                        │
                              ▲ JWKS                                     └───┬───────────────┬────┘
                              │                                              │ 3. jwt-bearer │ 4. Bearer AT
┌─────────────────────────────┴─────────────┐                                ▼               ▼
│ Keycloak 26.7  ema-keycloak :8090         │◀───────────────────────────────┘   ┌──────────────────────┐
│ realm reshapr-ema                         │  access_token (aud = exposition)   │ Reshapr proxy :7777  │
│ = MCP Resource Authorization Server       │───────────────────────────────────▶│ SecureEndpointFilter │
│ feature identity-assertion-jwt (Receiver) │                                    │ validates iss/aud/   │
└───────────────────────────────────────────┘        JWKS (certs) ◀──────────────│ scope via KC JWKS    │
                                                                                 └──────────┬───────────┘
                                                                        EDS sync            │
                                                                  ┌─────────────────────────┴──┐
                                                                  │ Reshapr control-plane :5555│
                                                                  │ plan: authorizationServers │
                                                                  │  = KC realm, jwksUri=certs │
                                                                  └────────────────────────────┘
```

| Component | Role in the EMA spec | Where |
|---|---|---|
| `ema-idp-mock` (nginx) | **Enterprise IdP / ID-JAG Issuer** — serves the JWKS; ID-JAGs are forged offline by `idp-mock/forge-idjag.mjs` (Keycloak does not implement the Issuer side yet) | `idp-mock/` |
| `ema-keycloak` | **MCP Resource Authorization Server / ID-JAG Receiver** — experimental feature `identity-assertion-jwt` | `keycloak/realm/reshapr-ema-realm.json` |
| Reshapr proxy | **MCP Resource Server** — publishes RFC 9728 PRM, validates access tokens | your local stack |
| Reshapr control-plane | policy: OAuth2 configuration plan trusting the Keycloak realm | your local stack |

## Prerequisites

- Docker, `node` ≥ 20, `curl`, `jq`
- A running Reshapr control-plane (`localhost:5555`, dev data: user `admin`/`password`, org `reshapr`,
  gateway group `1`) and proxy (`localhost:7777`) — dev mode or your usual local compose. Only needed
  for steps `00` and `03`; steps `01`/`02` work with the sandbox alone.

## Run

```bash
cd dev/ema-authentication
node idp-mock/generate-keys.mjs      # RSA key pair + JWKS for the mocked IdP (gitignored)
docker compose up -d                 # Keycloak (8090) + IdP mock (8091); realm auto-imported

./scripts/00-provision-reshapr.sh    # service + OAuth2 plan + exposition 'ema-weather' ; checks PRM + 401 challenge
./scripts/01-generate-idjag.sh       # IdP issues the ID-JAG (typ oauth-id-jag+jwt, aud = KC realm, resource = exposition)
./scripts/02-exchange-idjag.sh       # jwt-bearer grant at Keycloak → access token audience-bound to the exposition
./scripts/03-call-mcp.sh             # initialize + tools/list through the proxy, then negative cases

./scripts/99-cleanup.sh              # remove exposition / plan / service from Reshapr
docker compose down -v
```

All settings (URLs, credentials, exposition name, scope…) are overridable via environment
variables — see `scripts/env.sh`. Keycloak admin console: http://localhost:8090 (`admin`/`admin`).

## What the Keycloak realm configures (the "Keycloak recipe")

This is the configuration a customer operating its own Keycloak would need so that it serves as the
Authorization Server of a Reshapr exposition (scenario A):

1. **Start Keycloak with** `--features=identity-assertion-jwt` (experimental in 26.7).
2. **Identity provider `id-jag`** (type `oidc`) = trust anchor for the enterprise IdP:
   `issuer`, `jwksUrl` + `useJwksUrl=true`, `validateSignature=true`, and the ID-JAG specific flags
   `jwtAuthorizationGrantEnabled=true`, `jwtAuthorizationGrantAssertionReuseAllowed=false`
   (single-use `jti`), `jwtAuthorizationGrantMaxAllowedAssertionExpiration=300`.
3. **Confidential client `mcp-client`** with attributes `oauth2.jwt.authorization.grant.enabled=true`
   and `oauth2.jwt.authorization.grant.idp=id-jag` → allowed to present assertions from that IdP.
4. **Client scope `mcp.read`** carrying an **audience mapper** whose value is the *canonical MCP
   resource URI* of the exposition (`http://localhost:7777/mcp/reshapr/ema-weather`). This is what
   makes the issued access token audience-restricted as required by EMA §5.1 and enforced by the proxy
   (`SecureEndpointFilter.validateAudience`, step A1). One scope/mapper per exposition.
5. **Account linking**: the user must already exist and have a *federated identity* on `id-jag`
   whose `userId` equals the `sub` of the ID-JAG (`U-alice-001` → `alice`). No JIT provisioning.

Reshapr side: a configuration plan with `authorizationServers=[<realm issuer>]`,
`jwksUri=<realm>/protocol/openid-connect/certs`, `scopes=[mcp.read]`.

## Observed behaviour (Keycloak 26.7.x, run of 2026-09-09)

End-to-end run (`00` → `03`) against a local control-plane + proxy — **all 6 checks green**:
`initialize` 200 (session issued), `tools/list` 200 (`get_v1_forecast`), ID-JAG replay refused by
Keycloak, **403 from the proxy when the token (aud = `/mcp/reshapr/ema-weather`) is presented on the
id-based path `/mcp/{id}`** (A1 audience binding), 401 + `resource_metadata` challenge without token,
401 `invalid_token` with a tampered payload.

> Testing tip: to tamper a JWT, flip a character in the *payload* or the *middle* of the signature.
> Changing the last signature character only touches base64url padding bits (2048-bit signature =
> 342 chars, 4 padding bits) — the decoded bytes are identical and the token stays valid.

Nominal exchange (`02-exchange-idjag.sh`) — the ID-JAG is accepted and Keycloak issues:

```json
{ "iss": "http://localhost:8090/realms/reshapr-ema", "sub": "<alice uuid>", "email": "alice@acme.example",
  "aud": "http://localhost:7777/mcp/reshapr/ema-weather", "scope": "email mcp.read", "azp": "mcp-client",
  "jti": "…", "exp": …, "iat": … }    // header typ=JWT, RS256, kid from the realm JWKS
```
→ every claim required by `SecureEndpointFilter` is present (`sub iat exp jti aud`, `iss` ∈ authorization
servers, `aud` = canonical URI, `scope ⊇ mcp.read`, JOSE `typ` accepted).

Negative cases at the Authorization Server:

| Case | Result |
|---|---|
| Same ID-JAG presented twice | `400 invalid_grant — Token reuse detected` ✔ |
| `aud` ≠ realm issuer | `400 invalid_grant — Invalid token audience` ✔ |
| `sub` without federated identity | `400 invalid_grant — User not found` ✔ (no JIT) |
| header `typ=JWT` instead of `oauth-id-jag+jwt` | **accepted** (Keycloak does not enforce the typ) |

## Limitations / findings to feed Phase B

- **Discovery (EMA §6)**: Keycloak does not publish `authorization_grant_profiles_supported` in
  `/.well-known/oauth-authorization-server`; an MCP client cannot detect ID-JAG support from metadata.
  Our future `auth-server` must publish `urn:ietf:params:oauth:grant-profile:id-jag`.
- **Issuer side simulated**: Keycloak cannot yet issue ID-JAGs (token-exchange → `id-jag`), so the SSO
  + token-exchange steps are replaced by an offline forge with the IdP private key. Semantics of the
  produced ID-JAG follow EMA §4.3 / draft §3.1.
- **`resource` claim ignored by Keycloak**: the audience of the access token comes from the client-scope
  mapper, not from the ID-JAG `resource`. An AS that binds `aud` to `resource` dynamically (as
  Reshapr's auth-server will, resolving the exposition from the registry) is stricter and simpler
  to operate than one static scope+mapper per exposition.
- **Account linking is mandatory** in Keycloak (federated identity per user). Reshapr's auth-server
  will not need local accounts: it can propagate `sub`/`email` (+ original `iss`) straight into the
  access token — see plan step B6.
- **`typ` header** not verified by Keycloak; the auth-server should enforce `oauth-id-jag+jwt`.
- **Canonical URI strictness**: the proxy compares `aud` to `scheme://fqdn + request path`; the same
  exposition reachable through `/mcp/{id}` or the legacy 3-segment path has different canonical URIs —
  keep clients on the named form `/mcp/{org}/{name}` (this is the URI published in the PRM). Follow-up
  from A1: factor the canonical URI computation between `SecureEndpointFilter` and `WellKnownController`.

## Files

```
dev/ema-authentication/
├── docker-compose.yml                 Keycloak 26.7 (--features=identity-assertion-jwt) + nginx IdP mock
├── keycloak/realm/reshapr-ema-realm.json
├── idp-mock/
│   ├── generate-keys.mjs              RSA keys → keys/private.pem (gitignored) + www/jwks.json
│   ├── forge-idjag.mjs                ID-JAG forge (all claims overridable)
│   └── www/                           served by nginx: jwks.json, .well-known/openid-configuration
└── scripts/
    ├── env.sh                         shared settings (override via env vars)
    ├── 00-provision-reshapr.sh        Reshapr runtime objects via REST API + PRM / challenge checks
    ├── 01-generate-idjag.sh
    ├── 02-exchange-idjag.sh           jwt-bearer grant + claim sanity checks
    ├── 03-call-mcp.sh                 MCP calls via proxy + negative cases (replay, 401, tampering)
    └── 99-cleanup.sh
```
