#!/usr/bin/env bash
# Step 3 — Call the MCP exposition through the Reshapr proxy with the access token obtained via ID-JAG,
# then exercise the negative paths that matter for EMA:
#   - replayed ID-JAG must be rejected by the Authorization Server (single-use assertion)
#   - access token whose audience is NOT the canonical exposition URI must be rejected by the proxy (403)
#   - unauthenticated call must be challenged (401 + resource_metadata)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

ACCESS_TOKEN="${1:-$(cat "${STATE_DIR}/access_token.jwt" 2>/dev/null || true)}"
[[ -n "${ACCESS_TOKEN}" ]] || die "no access token — run 02-exchange-idjag.sh first"

# Session-based Streamable HTTP: 'initialize' returns an MCP-Session-Id that subsequent calls must carry.
MCP_PROTOCOL_VERSION="${MCP_PROTOCOL_VERSION:-2025-06-18}"
SESSION_FILE="$(mktemp)"; trap 'rm -f "${SESSION_FILE}"' EXIT

mcp() { # mcp <token|-> <json-rpc body> -> prints "HTTP_CODE\nBODY" ; captures MCP-Session-Id when returned
  local token="$1" body="$2" auth=() sess=() hdrs
  hdrs="$(mktemp)"
  [[ "${token}" != "-" ]] && auth=(-H "Authorization: Bearer ${token}")
  local sid; sid="$(cat "${SESSION_FILE}")"
  [[ -n "${sid}" ]] && sess=(-H "MCP-Session-Id: ${sid}")
  curl -s -w '\n%{http_code}' -D "${hdrs}" -X POST "${MCP_RESOURCE}" ${auth[@]+"${auth[@]}"} ${sess[@]+"${sess[@]}"} \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "MCP-Protocol-Version: ${MCP_PROTOCOL_VERSION}" -d "${body}"
  # mcp() runs inside $(...) sub-shells, so persist the session id through a file rather than a variable.
  sid="$(awk 'tolower($1)=="mcp-session-id:" {print $2}' "${hdrs}" | tr -d '\r')"
  [[ -n "${sid}" ]] && printf '%s' "${sid}" > "${SESSION_FILE}"
  rm -f "${hdrs}"
}
# Streamable HTTP may answer with SSE; keep only JSON data lines.
json_of() { sed '$d' | sed -n 's/^data: //p; /^{/p' | head -n1; }
code_of() { tail -n1; }

INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ema-sandbox","version":"0.1"}}}'
LIST='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

c_step "1. initialize with ID-JAG-derived access token → ${MCP_RESOURCE}"
R="$(mcp "${ACCESS_TOKEN}" "${INIT}")"
[[ "$(code_of <<<"$R")" == "200" ]] && c_ok "HTTP 200" || { c_ko "HTTP $(code_of <<<"$R")"; sed '$d' <<<"$R"; exit 1; }
json_of <<<"$R" | jq '.result.serverInfo, .result.capabilities' 2>/dev/null || json_of <<<"$R"
[[ -s "${SESSION_FILE}" ]] && c_info "MCP-Session-Id: $(cat "${SESSION_FILE}")"

c_step "2. tools/list"
R="$(mcp "${ACCESS_TOKEN}" "${LIST}")"
[[ "$(code_of <<<"$R")" == "200" ]] && c_ok "HTTP 200" || c_ko "HTTP $(code_of <<<"$R")"
J="$(json_of <<<"$R")"
if jq -e '.error' <<<"$J" >/dev/null 2>&1; then
  c_ko "JSON-RPC error: $(jq -r '.error.message' <<<"$J")"
else
  jq -r '.result.tools[]?.name' <<<"$J" | sed 's/^/  - /' | head -20
  c_ok "$(jq '.result.tools | length' <<<"$J") tools listed"
fi

c_step "3. Negative: replay the same ID-JAG at the Authorization Server (must be refused)"
if [[ -f "${STATE_DIR}/idjag.jwt" ]]; then
  R="$(curl -s -w '\n%{http_code}' -X POST "${KC_TOKEN_ENDPOINT}" \
    --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer" \
    --data-urlencode "assertion=$(cat "${STATE_DIR}/idjag.jwt")" \
    --data-urlencode "client_id=${KC_CLIENT_ID}" --data-urlencode "client_secret=${KC_CLIENT_SECRET}")"
  CODE="$(code_of <<<"$R")"
  [[ "${CODE}" =~ ^4 ]] && c_ok "replay refused (HTTP ${CODE}: $(sed '$d' <<<"$R" | jq -r '.error + " - " + (.error_description // "")' 2>/dev/null))" \
                        || c_ko "replay ACCEPTED (HTTP ${CODE}) — jwtAuthorizationGrantAssertionReuseAllowed should be false"
fi

c_step "4. Negative: audience mismatch → proxy must answer 403"
# The token's aud is bound to the named URI ${MCP_RESOURCE}. Calling the SAME exposition through its
# id-based path yields a different canonical URI, so SecureEndpointFilter.validateAudience must refuse it.
if [[ -f "${STATE_DIR}/exposition.id" ]]; then
  BY_ID="${RESHAPR_PROXY_URL}/mcp/$(cat "${STATE_DIR}/exposition.id")"
  R="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BY_ID}" -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "${INIT}")"
  [[ "$R" == "403" ]] && c_ok "403 on ${BY_ID} (aud bound to ${MCP_RESOURCE})" || c_ko "expected 403 on ${BY_ID}, got HTTP $R"
else
  c_info "no exposition id in state — skipped"
fi

c_step "5. Negative: unauthenticated call → 401 + WWW-Authenticate resource_metadata"
R="$(curl -s -o /dev/null -D - -X POST "${MCP_RESOURCE}" -H 'Content-Type: application/json' -d "${INIT}")"
grep -qi '^HTTP/.* 401' <<<"$R" && c_ok "401" || c_ko "expected 401"
grep -i '^www-authenticate' <<<"$R" | sed 's/^/  /'

c_step "6. Negative: tampered token (payload claim flipped → signature mismatch) → 401 invalid_token"
# Do NOT just change the last character: it only carries base64url padding bits (342 chars for a
# 2048-bit signature), so the decoded signature stays identical and the token remains valid.
H="${ACCESS_TOKEN%%.*}"; REST="${ACCESS_TOKEN#*.}"; P="${REST%%.*}"; S="${REST#*.}"
FLIP="$([[ "${P:20:1}" == "A" ]] && echo B || echo A)"
TAMPERED="${H}.${P:0:20}${FLIP}${P:21}.${S}"
R="$(curl -s -o /dev/null -D - -X POST "${MCP_RESOURCE}" -H "Authorization: Bearer ${TAMPERED}" -H 'Content-Type: application/json' -d "${INIT}")"
grep -qi '^HTTP/.* 401' <<<"$R" && c_ok "401" || c_ko "expected 401"
grep -i '^www-authenticate' <<<"$R" | sed 's/^/  /'

echo
c_ok "EMA scenario A (pass-through, external Authorization Server) exercised end-to-end."
