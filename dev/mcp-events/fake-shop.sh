#!/usr/bin/env bash
# Fake "shop" application: publishes an OrderCreated event on orders.created every few seconds.
# Alternates two organizations so the endpoint filter (filter=json:$.org==foobar) can be demonstrated.
# Usage: ./fake-shop.sh [interval-seconds]
set -euo pipefail

INTERVAL="${1:-3}"
CONTAINER="${REDPANDA_CONTAINER:-reshapr-demo-redpanda}"
ORGS=(foobar acme)
i=0

echo "Publishing an order every ${INTERVAL}s on orders.created (Ctrl-C to stop)"
while true; do
  i=$((i + 1))
  org="${ORGS[$((i % 2))]}"
  amount=$(( (RANDOM % 9000) / 100 + 5 ))
  payload=$(printf '{"orderId":"ORD-%04d","org":"%s","customerId":"cust-%d","amount":%d}' "$i" "$org" $((RANDOM % 20)) "$amount")
  echo "$payload" | docker exec -i "$CONTAINER" rpk topic produce orders.created -k "ORD-$i" >/dev/null
  echo "-> $payload"
  sleep "$INTERVAL"
done
