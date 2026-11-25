#!/usr/bin/env bash
# Minimal client of the MCP Events extension (io.modelcontextprotocol/events): events/list, then an events/poll loop.
# Usage: ./mcp-events-poll.sh <mcp-endpoint-url> [event-name] [arguments-json] [api-key]
#   e.g. ./mcp-events-poll.sh http://localhost:7777/mcp/<expositionId> orderCreated '{"org":"foobar"}'
set -euo pipefail

URL="${1:?usage: $0 <mcp-endpoint-url> [event-name] [arguments-json] [api-key]}"
NAME="${2:-orderCreated}"
ARGS="${3:-{\}}"
KEY="${4:-}"
PV="2026-07-28"
AUTH=()
[ -n "$KEY" ] && AUTH=(-H "Authorization: Bearer $KEY")

rpc() { # method, params-json (without _meta)
  curl -sS "${AUTH[@]}" -H 'Content-Type: application/json' -H "MCP-Protocol-Version: $PV" \
    -H "Mcp-Method: $1" ${3:+-H "Mcp-Name: $3"} \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$RANDOM,\"method\":\"$1\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$PV\"}$2}}" "$URL"
}

echo "== events/list"
rpc events/list "" | jq -c '.result.events[] | {name, delivery, inputSchema: (.inputSchema.properties | keys)}'

echo "== events/poll $NAME $ARGS (Ctrl-C to stop)"
CURSOR=null
while true; do
  RES=$(rpc events/poll ",\"name\":\"$NAME\",\"arguments\":$ARGS,\"cursor\":$CURSOR" "$NAME")
  echo "$RES" | jq -e '.error' >/dev/null 2>&1 && { echo "$RES" | jq -c .error; exit 1; }
  echo "$RES" | jq -r '.result | (.events[] | "• \(.eventId) \(.data | tojson)"), (if .truncated then "  (truncated: possible gap)" else empty end)'
  CURSOR=$(echo "$RES" | jq -c '.result.cursor')
  [ "$(echo "$RES" | jq -r '.result.hasMore')" = "true" ] || sleep "$(echo "$RES" | jq -r '(.result.nextPollMs / 1000) | if . < 1 then 1 else . end')"
done
