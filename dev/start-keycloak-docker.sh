#!/usr/bin/env bash
# Start Keycloak for local Reshapr OIDC dev (realm 3rdparty on port 8888).
# Single terminal: starts container, fixes master sslRequired, then follows logs.
set -euo pipefail

CONTAINER_NAME="${RESHAPR_KEYCLOAK_CONTAINER:-reshapr-keycloak-dev}"
KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.3.0}"
HOST_PORT="${KEYCLOAK_HOST_PORT:-8888}"

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
  start-dev --hostname "http://localhost:${HOST_PORT}" --import-realm

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

echo "Disabling SSL requirement on master realm (local dev only) ..."
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh config credentials \
  --server "http://localhost:8080" --realm master --user admin --password admin

docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE

echo ""
echo "Keycloak ready:"
echo "  Admin console: http://localhost:${HOST_PORT}/admin  (admin / admin)"
echo "  Realm OIDC:    3rdparty  (client reshapr-ctrl, user laurent / laurent)"
echo "  Stop:          docker stop ${CONTAINER_NAME}"
echo ""
echo "Following logs (Ctrl+C stops tail only; container keeps running until docker stop):"
docker logs -f "${CONTAINER_NAME}"