#!/usr/bin/env bash
# Remove everything 00-provision-reshapr.sh created in Reshapr (exposition, plan, service) and local state.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

TOKEN="$(curl -sf -X POST "${RESHAPR_CTRL_URL}/auth/login/reshapr" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${RESHAPR_USER}\",\"password\":\"${RESHAPR_PASSWORD}\"}")" || die "login failed"
AUTH=(-H "Authorization: Bearer ${TOKEN}")

del() { # del <label> <path> <idfile>
  local f="${STATE_DIR}/$3"
  [[ -f "$f" ]] || return 0
  local code; code="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${RESHAPR_CTRL_URL}$2/$(cat "$f")" "${AUTH[@]}")"
  [[ "$code" =~ ^(200|204|404)$ ]] && { c_ok "$1 $(cat "$f") deleted (HTTP $code)"; rm -f "$f"; } || c_ko "$1 deletion failed (HTTP $code)"
}
del exposition /api/v1/expositions exposition.id
del "configuration plan" /api/v1/configurationPlans plan.id
del service /api/v1/services service.id
rm -f "${STATE_DIR}"/*.jwt
c_ok "state cleaned (${STATE_DIR})"
