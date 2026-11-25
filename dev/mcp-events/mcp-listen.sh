#!/usr/bin/env bash
# Minimal MCP client for the demo (curl + jq): opens subscriptions/listen, and on every
# notifications/resources/updated calls resources/read to fetch the new events.
# Usage: ./mcp-listen.sh <mcp-endpoint-url> [bearer-or-api-key]
#   e.g. ./mcp-listen.sh http://localhost:7777/mcp/<expositionId>
set -euo pipefail

URL="${1:?usage: $0 <mcp-endpoint-url> [api-key]}"
KEY="${2:-}"
PV="2026-07-28"
SUB_ID=1
AUTH=()
[ -n "$KEY" ] && AUTH=(-H "Authorization: Bearer $KEY")

META="\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$PV\",\"io.modelcontextprotocol/subscriptionId\":$SUB_ID}"

read_events() {
  local uri="$1"
  curl -sS "${AUTH[@]}" -H 'Content-Type: application/json' -H "MCP-Protocol-Version: $PV" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$((RANDOM + 100)),\"method\":\"resources/read\",\"params\":{$META,\"uri\":\"$uri\"}}" "$URL" \
    | jq -r '.result.contents[0].text | fromjson | "   events=\(.events | length) lost=\(.lostEvents)", (.events[] | "   • \(.payload | tojson)")'
}

echo "Listening on $URL ..."
curl -sS -N "${AUTH[@]}" -H 'Content-Type: application/json' -H "MCP-Protocol-Version: $PV" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":$SUB_ID,\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$PV\"},\"notifications\":{\"resourceSubscriptions\":[\"events://orderCreated\"]}}}" \
  "$URL" | while read -r line; do
    case "$line" in
      data:*)
        json="${line#data: }"
        method=$(echo "$json" | jq -r '.method // "response"')
        echo "[$method]"
        if [ "$method" = "notifications/resources/updated" ]; then
          read_events "$(echo "$json" | jq -r '.params.uri')"
        fi
        ;;
    esac
  done
