#!/usr/bin/env bash
# Minimal client of the MCP Events extension: events/stream (push over SSE), with automatic resume from the last cursor.
# Usage: ./mcp-events-stream.sh <mcp-endpoint-url> [event-name] [arguments-json] [api-key]
#   e.g. ./mcp-events-stream.sh http://localhost:7777/mcp/<expositionId> orderCreated '{"org":"foobar"}'
set -euo pipefail

URL="${1:?usage: $0 <mcp-endpoint-url> [event-name] [arguments-json] [api-key]}"
NAME="${2:-orderCreated}"
ARGS="${3:-{\}}"
KEY="${4:-}"
PV="2026-07-28"
AUTH=()
[ -n "$KEY" ] && AUTH=(-H "Authorization: Bearer $KEY")

CURSOR=null
echo "== events/stream $NAME $ARGS (Ctrl-C to stop)"
while true; do
  curl -sS -N "${AUTH[@]}" -H 'Content-Type: application/json' -H "MCP-Protocol-Version: $PV" \
    -H 'Mcp-Method: events/stream' -H "Mcp-Name: $NAME" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$RANDOM,\"method\":\"events/stream\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$PV\"},\"name\":\"$NAME\",\"arguments\":$ARGS,\"cursor\":$CURSOR,\"heartbeatMs\":10000}}" \
    "$URL" | while IFS= read -r line; do
      case "$line" in
        data:*) ;;
        *) continue ;;
      esac
      MSG="${line#data:}"
      echo "$MSG" | jq -r '
        if .error then "! error \(.error.code) \(.error.message)"
        elif .method == "notifications/events/active" then "• active (truncated=\(.params.truncated)) heartbeat=\(.params.heartbeatMs)ms"
        elif .method == "notifications/events/event" then "• \(.params.eventId) \(.params.data | tojson)"
        elif .method == "notifications/events/heartbeat" then "  ♥"
        elif .method == "notifications/events/terminated" then "• terminated: \(.params.error.message)"
        elif .method == "notifications/events/error" then "! transient: \(.params.error.message)"
        else empty end'
      C=$(echo "$MSG" | jq -c '.params.cursor // empty' 2>/dev/null || true)
      [ -n "$C" ] && echo "$C" > /tmp/.mcp-events-stream-cursor
    done || true
  [ -s /tmp/.mcp-events-stream-cursor ] && CURSOR=$(cat /tmp/.mcp-events-stream-cursor)
  echo "-- stream closed, resuming in 2s from cursor $CURSOR"
  sleep 2
done
