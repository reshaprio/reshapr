#!/usr/bin/env bash
# Step 1 — Simulate the enterprise IdP issuing an ID-JAG (EMA spec §4 Token Exchange).
# In a real deployment the MCP client would POST grant_type=token-exchange with its ID Token to the IdP;
# here the mocked IdP forges the resulting ID-JAG directly with its private key.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

[[ -f "${SANDBOX_DIR}/idp-mock/keys/private.pem" ]] || die "no IdP keys — run: node idp-mock/generate-keys.mjs (then restart the stack)"

c_step "Forge ID-JAG (iss=${IDP_ISSUER}, aud=${KC_ISSUER}, resource=${MCP_RESOURCE})"
IDJAG="$(node "${SANDBOX_DIR}/idp-mock/forge-idjag.mjs" \
  --iss "${IDP_ISSUER}" --aud "${KC_ISSUER}" --resource "${MCP_RESOURCE}" \
  --client-id "${KC_CLIENT_ID}" --scope "${MCP_SCOPE}" "$@")"
echo "${IDJAG}" > "${STATE_DIR}/idjag.jwt"

c_info "header : $(jwt_header "${IDJAG}")"
c_info "payload:"; jwt_payload "${IDJAG}" | jq .
c_ok "ID-JAG written to ${STATE_DIR}/idjag.jwt"
