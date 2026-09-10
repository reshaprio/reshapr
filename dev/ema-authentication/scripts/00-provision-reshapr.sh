#!/usr/bin/env bash
# Step 0 — Provision Reshapr (runtime configuration only, through the existing public REST API):
#   service (Open-Meteo OpenAPI) -> OAuth2 configuration plan trusting the Keycloak realm -> named exposition.
# Then wait for the proxy to publish the RFC 9728 Protected Resource Metadata for that exposition.
#
# Nothing in Reshapr's code or install/ stack is modified; run scripts/99-cleanup.sh to undo.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

c_step "Login to Reshapr control-plane (${RESHAPR_CTRL_URL}) as '${RESHAPR_USER}'"
TOKEN="$(curl -sf -X POST "${RESHAPR_CTRL_URL}/auth/login/reshapr" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${RESHAPR_USER}\",\"password\":\"${RESHAPR_PASSWORD}\"}")" \
  || die "login failed — is the control-plane running with dev data?"
AUTH=(-H "Authorization: Bearer ${TOKEN}")
c_ok "authenticated"

if [[ -f "${STATE_DIR}/exposition.id" && "${1:-}" != "--force" ]]; then
  c_info "state already present in ${STATE_DIR} (exposition $(cat "${STATE_DIR}/exposition.id")); use --force to re-provision or 99-cleanup.sh first"
else
  c_step "Import OpenAPI artifact ($(basename "${OPENAPI_SPEC}"))"
  [[ -f "${OPENAPI_SPEC}" ]] || die "spec not found: ${OPENAPI_SPEC}"
  SERVICE_JSON="$(curl -sf -X POST "${RESHAPR_CTRL_URL}/api/v1/artifacts" "${AUTH[@]}" \
    -F "file=@${OPENAPI_SPEC}" -F "mainArtifact=true" \
    -F "serviceName=EMA Weather" -F "serviceVersion=1.0.0")" || die "artifact import failed"
  SERVICE_ID="$(jq -r .id <<<"${SERVICE_JSON}")"
  echo "${SERVICE_ID}" > "${STATE_DIR}/service.id"
  c_ok "service '$(jq -r .name <<<"${SERVICE_JSON}")' id=${SERVICE_ID}"

  c_step "Create OAuth2 configuration plan trusting Keycloak realm '${KC_REALM}'"
  PLAN_JSON="$(curl -sf -X POST "${RESHAPR_CTRL_URL}/api/v1/configurationPlans" "${AUTH[@]}" \
    -H 'Content-Type: application/json' -d @- <<EOF
{
  "name": "ema-keycloak",
  "serviceId": "${SERVICE_ID}",
  "description": "EMA sandbox: tokens issued by Keycloak after an ID-JAG jwt-bearer grant",
  "backendEndpoint": "${BACKEND_ENDPOINT}",
  "oauth2Configuration": {
    "authorizationServers": ["${KC_ISSUER}"],
    "jwksUri": "${KC_JWKS_URI}",
    "scopes": ["${MCP_SCOPE}"]
  },
  "cachePolicy": { "ttlMs": 30000, "cacheScope": "public" },
  "audit": true
}
EOF
  )" || die "configuration plan creation failed"
  PLAN_ID="$(jq -r .id <<<"${PLAN_JSON}")"
  echo "${PLAN_ID}" > "${STATE_DIR}/plan.id"
  c_ok "plan '$(jq -r .name <<<"${PLAN_JSON}")' id=${PLAN_ID}"

  c_step "Create named exposition '${EXPO_NAME}' on gateway group ${RESHAPR_GATEWAY_GROUP_ID}"
  EXPO_JSON="$(curl -sf -X POST "${RESHAPR_CTRL_URL}/api/v1/expositions" "${AUTH[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"configurationPlanId\":\"${PLAN_ID}\",\"gatewayGroupId\":\"${RESHAPR_GATEWAY_GROUP_ID}\",\"name\":\"${EXPO_NAME}\"}")" \
    || die "exposition creation failed (name '${EXPO_NAME}' already used? run 99-cleanup.sh)"
  EXPO_ID="$(jq -r .id <<<"${EXPO_JSON}")"
  echo "${EXPO_ID}" > "${STATE_DIR}/exposition.id"
  c_ok "exposition id=${EXPO_ID}"
fi

c_step "Wait for the proxy to expose RFC 9728 metadata for ${MCP_RESOURCE}"
PRM_URL="${RESHAPR_PROXY_URL}/.well-known/oauth-protected-resource/mcp/${RESHAPR_ORG}/${EXPO_NAME}"
for i in $(seq 1 30); do
  if PRM="$(curl -sf "${PRM_URL}")"; then
    echo "${PRM}" | jq .
    [[ "$(jq -r '.authorization_servers[0]' <<<"${PRM}")" == "${KC_ISSUER}" ]] \
      && c_ok "PRM published; authorization server = ${KC_ISSUER}" \
      || c_ko "PRM authorization_servers does not match ${KC_ISSUER}"
    break
  fi
  sleep 1
  [[ $i -eq 30 ]] && die "PRM not available at ${PRM_URL} — is the proxy running and synced?"
done

c_step "Unauthenticated MCP call must be challenged (401 + WWW-Authenticate resource_metadata)"
curl -s -o /dev/null -D - -X POST "${MCP_RESOURCE}" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ema","version":"0"}}}' \
  | grep -iE '^(HTTP/|www-authenticate)' | sed 's/^/  /'

c_step "Keycloak RFC 8414 metadata (${KC_ISSUER})"
if KC_META="$(curl -sf "${KC_ISSUER}/.well-known/oauth-authorization-server")"; then
  jq '{issuer, token_endpoint, jwks_uri, grant_types_supported, authorization_grant_profiles_supported}' <<<"${KC_META}"
  jq -e '.grant_types_supported | index("urn:ietf:params:oauth:grant-type:jwt-bearer")' <<<"${KC_META}" >/dev/null \
    && c_ok "jwt-bearer grant advertised" || c_ko "jwt-bearer grant NOT advertised"
  jq -e '.authorization_grant_profiles_supported // [] | index("urn:ietf:params:oauth:grant-profile:id-jag")' <<<"${KC_META}" >/dev/null \
    && c_ok "id-jag grant profile advertised" \
    || c_info "authorization_grant_profiles_supported not published by Keycloak (known limitation: MCP client discovery §6 not testable here)"
else
  c_ko "Keycloak not reachable at ${KC_ISSUER} — run 'docker compose up -d' in ${SANDBOX_DIR}"
fi
