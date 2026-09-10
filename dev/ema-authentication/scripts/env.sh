#!/usr/bin/env bash
# Shared settings for the EMA sandbox scripts. Override any of them from the environment.

SANDBOX_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${SANDBOX_DIR}/.state"
mkdir -p "${STATE_DIR}"

# --- Reshapr (existing local platform, NOT managed by this sandbox) ---------------------------
RESHAPR_CTRL_URL="${RESHAPR_CTRL_URL:-http://localhost:5555}"
RESHAPR_PROXY_URL="${RESHAPR_PROXY_URL:-http://localhost:7777}"
RESHAPR_USER="${RESHAPR_USER:-admin}"
RESHAPR_PASSWORD="${RESHAPR_PASSWORD:-password}"
RESHAPR_ORG="${RESHAPR_ORG:-reshapr}"
RESHAPR_GATEWAY_GROUP_ID="${RESHAPR_GATEWAY_GROUP_ID:-1}"
OPENAPI_SPEC="${OPENAPI_SPEC:-${SANDBOX_DIR}/../open-meteo-openapi.yml}"
BACKEND_ENDPOINT="${BACKEND_ENDPOINT:-https://api.open-meteo.com}"

# The exposition name drives the canonical MCP resource URI: {proxy}/mcp/{org}/{name}
EXPO_NAME="${EXPO_NAME:-ema-weather}"
MCP_RESOURCE="${RESHAPR_PROXY_URL}/mcp/${RESHAPR_ORG}/${EXPO_NAME}"

# --- Keycloak = MCP Resource Authorization Server (ID-JAG receiver) ---------------------------
KC_URL="${KC_URL:-http://localhost:8090}"
KC_REALM="${KC_REALM:-reshapr-ema}"
KC_ISSUER="${KC_URL}/realms/${KC_REALM}"
KC_TOKEN_ENDPOINT="${KC_ISSUER}/protocol/openid-connect/token"
KC_JWKS_URI="${KC_ISSUER}/protocol/openid-connect/certs"
KC_CLIENT_ID="${KC_CLIENT_ID:-mcp-client}"
KC_CLIENT_SECRET="${KC_CLIENT_SECRET:-mcp-client-secret}"
MCP_SCOPE="${MCP_SCOPE:-mcp.read}"

# --- Mocked enterprise IdP (ID-JAG issuer) ---------------------------------------------------
IDP_ISSUER="${EMA_IDP_ISSUER:-http://localhost:8091}"

# --- helpers ---------------------------------------------------------------------------------
: "${NO_COLOR:=}"
c_ok()   { printf '\033[32m✔ %s\033[0m\n' "$*"; }
c_ko()   { printf '\033[31m✘ %s\033[0m\n' "$*" >&2; }
c_info() { printf '\033[36m• %s\033[0m\n' "$*"; }
c_step() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
die()    { c_ko "$*"; exit 1; }
need()   { command -v "$1" >/dev/null 2>&1 || die "'$1' is required"; }

# Decode the payload of a compact JWT (no verification) — for display only.
jwt_payload() { cut -d. -f2 <<<"$1" | tr '_-' '/+' | awk '{ l=length($0)%4; if(l==2) $0=$0"=="; else if(l==3) $0=$0"="; print }' | base64 -d 2>/dev/null; }
jwt_header()  { cut -d. -f1 <<<"$1" | tr '_-' '/+' | awk '{ l=length($0)%4; if(l==2) $0=$0"=="; else if(l==3) $0=$0"="; print }' | base64 -d 2>/dev/null; }

need curl; need jq; need node
