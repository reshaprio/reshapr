#!/usr/bin/env bash
# Fake order service: consumes commands from orders.commands.create (what the MCP tool "createOrder"
# publishes) and answers with an OrderCreated event on orders.created, closing the loop of the demo.
set -euo pipefail

CONTAINER="${REDPANDA_CONTAINER:-reshapr-demo-redpanda}"
n=0

echo "Waiting for commands on orders.commands.create (Ctrl-C to stop)"
docker exec "$CONTAINER" rpk topic consume orders.commands.create --offset end -f '%v\n' | while read -r command; do
  [ -z "$command" ] && continue
  n=$((n + 1))
  echo "<- command: $command"
  customer=$(echo "$command" | jq -r '.customerId // "unknown"')
  amount=$(echo "$command" | jq -r '.amount // 0')
  org=$(echo "$command" | jq -r '.org // "foobar"')
  event=$(jq -cn --arg id "$(printf 'CMD-%04d' "$n")" --arg org "$org" --arg c "$customer" --argjson a "$amount" \
    '{orderId:$id, org:$org, customerId:$c, amount:$a}')
  echo "$event" | docker exec -i "$CONTAINER" rpk topic produce orders.created >/dev/null
  echo "-> event: $event"
done
