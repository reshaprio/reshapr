#!/usr/bin/env bash
# Start Keycloak for local Reshapr OIDC dev (realms 3rdparty and backend on port 8888).
# Single terminal: starts container, fixes sslRequired on master + 3rdparty, then follows logs.
set -euo pipefail

CONTAINER_NAME="${RESHAPR_KEYCLOAK_CONTAINER:-reshapr-keycloak-dev}"
KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.6.0}"
HOST_PORT="${KEYCLOAK_HOST_PORT:-8888}"
: "${RESHAPR_NGROK_URL:?Set RESHAPR_NGROK_URL to your public ngrok URL}"
RESHAPR_NGROK_URL="${RESHAPR_NGROK_URL%/}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

docker rm -f "${CONTAINER_NAME}" 2>/dev/null || true

echo "Starting Keycloak (${CONTAINER_NAME}) on http://localhost:${HOST_PORT} ..."
docker run -d --rm --name "${CONTAINER_NAME}" \
  -v "${SCRIPT_DIR}:/opt/keycloak/data/import" \
  -p "${HOST_PORT}:8080" \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HOSTNAME=localhost \
  -e KC_HOSTNAME_STRICT=false \
  -e KC_HTTP_ENABLED=true \
  "${KEYCLOAK_IMAGE}" \
  start-dev --hostname "${RESHAPR_NGROK_URL}" --proxy-headers xforwarded --import-realm --hostname-backchannel-dynamic true --features cimd
# start-dev --hostname "http://localhost:${HOST_PORT}" --import-realm --hostname-backchannel-dynamic true

echo "Waiting for Keycloak to be ready ..."
READY=0
for i in $(seq 1 90); do
  if curl -sf "http://localhost:${HOST_PORT}/realms/3rdparty" >/dev/null 2>&1; then
    READY=1
    break
  fi
  printf '.'
  sleep 2
done
echo ""

if [ "${READY}" -ne 1 ]; then
  echo "Keycloak did not become ready in time. Logs:" >&2
  docker logs "${CONTAINER_NAME}" 2>&1 | tail -50 >&2
  exit 1
fi

echo "Disabling SSL requirement on master, 3rdparty and backend realms (local dev only) ..."
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh config credentials \
  --server "http://localhost:8080" --realm master --user admin --password admin

docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update realms/3rdparty -s sslRequired=NONE
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update realms/backend -s sslRequired=NONE

echo "Ensuring master admin password (admin console login) ..."
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh set-password -r master --username admin --new-password admin --temporary=false

echo "Configuring dev user laurent (realm import does not set a usable password in Keycloak 26) ..."
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh set-password -r 3rdparty --username laurent --new-password laurent --temporary=false
LAURENT_ID="$(
  docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh get users -r 3rdparty \
    -q username=laurent --fields id --format csv --noquotes 2>/dev/null | tail -1
)"
if [ -n "${LAURENT_ID}" ]; then
  docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update "users/${LAURENT_ID}" -r 3rdparty \
    -s email=laurent@example.com -s firstName=Laurent -s lastName=Test -s emailVerified=true
else
  echo "Warning: user laurent not found in realm 3rdparty; skipping profile update." >&2
fi

echo "Configuring dev user backend-user (realm import does not set a usable password in Keycloak 26) ..."
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh set-password -r backend --username backend-user --new-password backend-user --temporary=false
BACKEND_USER_ID="$(
  docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh get users -r backend \
    -q username=backend-user --fields id --format csv --noquotes 2>/dev/null | tail -1
)"
if [ -n "${BACKEND_USER_ID}" ]; then
  docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update "users/${BACKEND_USER_ID}" -r backend \
    -s email=backend-user@example.com -s firstName=Backend -s lastName=User -s emailVerified=true
else
  echo "Warning: user backend-user not found in realm backend; skipping profile update." >&2
fi

echo ""
echo "Keycloak ready:"
echo "  Admin console: http://localhost:${HOST_PORT}/admin"
echo "    Login realm: master (default) — user: admin / password: admin"
echo "    Then switch realm (top-left) to '3rdparty' → Users → Create user"
echo "  Reshapr OIDC:  http://localhost:5555  (test user laurent / laurent)"
echo "  Stop:          docker stop ${CONTAINER_NAME}"
echo ""
echo "Following logs (Ctrl+C stops tail only; container keeps running until docker stop):"
docker logs -f "${CONTAINER_NAME}"