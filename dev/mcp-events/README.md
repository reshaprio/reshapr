# MCP Events demo (AsyncAPI + Kafka)

Exposes an AsyncAPI 3 document as an MCP server:

| AsyncAPI operation | MCP primitive |
|---|---|
| `onOrderCreated` (`send`, topic `orders.created`) | resource `events://orderCreated` + `subscriptions/listen` |
| `createOrder` (`receive`, topic `orders.commands.create`) | tool `createOrder` (publishes to Kafka) |

Two client flavours are demonstrated on the same setup:

- **Demo 1, resources** (`mcp-listen.sh`): `events://` resources + `subscriptions/listen` + `resources/read`.
  Works with the gateway's own event model (protocol `2026-07-28`).
- **Demo 2, MCP Events extension** (`mcp-events-poll.sh`, `mcp-events-stream.sh`): `events/list`, then `events/poll`
  or `events/stream` (`io.modelcontextprotocol/events`).

Design: [docs/design-mcp-events.md](../../docs/design-mcp-events.md).

## Files

- `orders-asyncapi.yaml`: the AsyncAPI document.
- `docker-compose-redpanda.yml`, `start-redpanda.sh`, `stop-redpanda.sh`: Redpanda + console (http://localhost:8089), topics created.
- `fake-shop.sh`: fake application emitting `OrderCreated` events (alternating orgs `foobar` / `acme`).
- `fake-order-service.sh`: fake application consuming `createOrder` commands and answering with an event.
- `mcp-listen.sh`: demo 1 client (curl + jq): `subscriptions/listen`, then `resources/read` on each update.
- `mcp-events-poll.sh`: demo 2 client (curl + jq): `events/list`, then an `events/poll` loop with a cursor.
- `mcp-events-stream.sh`: demo 2 client: `events/stream` (SSE push, heartbeat, automatic resume from the last cursor).

Prerequisites: Docker, `jq`, `curl`. The scripts talk to Redpanda through `docker exec ... rpk`.

## Run it

1. Start the platform (control plane + gateway) as in [BUILD.md](../../BUILD.md) / `install/`.
   The gateway must be built from this branch (Kafka client + `ASYNC` support).
   - Gateway on the host (`./mvnw quarkus:dev` in `proxy/`): broker = `kafka://localhost:19092`.
   - Gateway in Docker: attach it to the `reshapr-events` network and use `kafka://redpanda:29092`.
2. Start the broker:
   ```sh
   ./start-redpanda.sh
   ```
3. Import and expose the service:
   ```sh
   reshapr import -f dev/events-demo/orders-asyncapi.yaml --be 'kafka://localhost:19092'
   ```
   To only receive the events of one organization, use the endpoint filter:
   `--be 'kafka://localhost:19092?filter=json:$.org==foobar'`.
   Note the MCP endpoint printed by the CLI (`/mcp/<expositionId>`).
4. Terminal A, the fake shop:
   ```sh
   ./fake-shop.sh 3
   ```
5. Terminal B, the MCP client (protocol `2026-07-28` is required for `subscriptions/listen`):
   ```sh
   ./mcp-listen.sh '<mcp-endpoint-url>'
   ```
   You get an `acknowledged` message, then `notifications/resources/updated` and the events read from the buffer.
   Nothing is consumed from Kafka until the first subscription opens.
6. Terminal C, the command path:
   ```sh
   ./fake-order-service.sh
   ```
   Call the `createOrder` tool from an MCP client with `{"customerId":"c1","amount":42,"org":"foobar"}`:
   the command lands on `orders.commands.create`, the fake service answers with an `OrderCreated` event,
   and Terminal B displays it.

### Demo 2: MCP Events extension

Same setup (steps 1 to 4), then in Terminal B:
```sh
./mcp-events-poll.sh '<mcp-endpoint-url>' orderCreated '{"org":"foobar"}'
# or, push instead of polling:
./mcp-events-stream.sh '<mcp-endpoint-url>' orderCreated '{"org":"foobar"}'
```
It lists the event types (name, delivery modes, subscription arguments), then polls: the first poll starts
"from now", later polls return the events after the cursor (`eventId` = `topic-partition-offset`).
Arguments are ANDed with the endpoint filter. Unlike demo 1, no consumer is kept: each poll reads Kafka
statelessly, so any gateway replica can answer any poll. Run `./fake-shop.sh 3` to see events arrive,
and restart the script with a stored cursor to replay what happened meanwhile (bounded by Kafka retention).

## Things to try

- Stop `mcp-listen.sh`, wait for the grace delay (30 s by default): the gateway stops its consumer
  (group `<replica-id>-<exposition-id>` disappears in the console).
- Run two gateway replicas: each has its own consumer group, so each one sees every message.
- Flood the topic (`./fake-shop.sh 0.01`) to see `lostEvents` when the buffer
  (`reshapr.gateway.events.buffer.size`) overflows.
- Change the filter to `json:$.org!=foobar` and re-import.

## Cleanup

```sh
./stop-redpanda.sh
```
