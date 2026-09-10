#!/usr/bin/env bash
# Step 2 — Exchange the ID-JAG for an MCP access token at the Resource Authorization Server (EMA spec §5):
#   POST token_endpoint  grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer  assertion=<ID-JAG>
# Keycloak validates the assertion against the 'id-jag' identity provider (signature via JWKS, iss, aud,
# exp, single use) and issues an access token bound to the MCP exposition audience.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

IDJAG="${1:-$(cat "${STATE_DIR}/idjag.jwt" 2>/dev/null || true)}"
[[ -n "${IDJAG}" ]] || die "no ID-JAG — run 01-generate-idjag.sh first"

c_step "jwt-bearer grant at ${KC_TOKEN_ENDPOINT} (client ${KC_CLIENT_ID})"
RESP="$(curl -s -w '\n%{http_code}' -X POST "${KC_TOKEN_ENDPOINT}" \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer" \
  --data-urlencode "assertion=${IDJAG}" \
  --data-urlencode "client_id=${KC_CLIENT_ID}" \
  --data-urlencode "client_secret=${KC_CLIENT_SECRET}" \
  --data-urlencode "scope=${MCP_SCOPE}")"
CODE="$(tail -n1 <<<"${RESP}")"
BODY="$(sed '$d' <<<"${RESP}")"

if [[ "${CODE}" != "200" ]]; then
  c_ko "token endpoint answered HTTP ${CODE}:"
  jq . <<<"${BODY}" 2>/dev/null || echo "${BODY}"
  exit 1
fi

ACCESS_TOKEN="$(jq -r .access_token <<<"${BODY}")"
echo "${ACCESS_TOKEN}" > "${STATE_DIR}/access_token.jwt"
jq '{token_type, expires_in, scope}' <<<"${BODY}"
c_info "access token header : $(jwt_header "${ACCESS_TOKEN}")"
c_info "access token claims :"
jwt_payload "${ACCESS_TOKEN}" | jq '{iss, sub, email, aud, scope, azp, jti, exp, iat}'

# Sanity checks against what the proxy will enforce (SecureEndpointFilter).
P="$(jwt_payload "${ACCESS_TOKEN}")"
[[ "$(jq -r .iss <<<"$P")" == "${KC_ISSUER}" ]] && c_ok "iss matches configured authorization server" || c_ko "iss mismatch"
jq -e --arg a "${MCP_RESOURCE}" '(.aud | if type=="array" then . else [.] end) | index($a)' <<<"$P" >/dev/null \
  && c_ok "aud contains canonical MCP resource ${MCP_RESOURCE}" || c_ko "aud does not contain ${MCP_RESOURCE} (proxy will answer 403)"
jq -e --arg s "${MCP_SCOPE}" '.scope | split(" ") | index($s)' <<<"$P" >/dev/null \
  && c_ok "scope contains ${MCP_SCOPE}" || c_ko "scope missing ${MCP_SCOPE}"
c_ok "access token written to ${STATE_DIR}/access_token.jwt"
