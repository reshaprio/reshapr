#!/usr/bin/env bash
# Start Redpanda (+ web console on http://localhost:8089) and create the demo topics.
set -euo pipefail
cd "$(dirname "$0")"

docker compose -f docker-compose-redpanda.yml up -d --wait redpanda
docker compose -f docker-compose-redpanda.yml up -d console

for topic in orders.created orders.commands.create; do
  docker exec reshapr-demo-redpanda rpk topic create "$topic" -p 1 -r 1 2>/dev/null || true
done

echo "Redpanda ready: localhost:19092 (host) / redpanda:29092 (reshapr-events network)"
echo "Console: http://localhost:8089"
