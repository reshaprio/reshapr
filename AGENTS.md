# AGENTS.md

## Project Overview

reShapr is a no-code MCP (Model Context Protocol) Server that transforms REST/GraphQL/gRPC APIs into LLM-friendly tools. It solves "Context Overload" by filtering and slimming API payloads before they reach LLMs.

## Architecture

Three core runtime services communicate via gRPC, plus a web UI and a CLI:

```
                             ┌─────────────────────────────────────┐
                             │         reShapr Platform            │
                             │                                     │
  ┌──────────┐  MCP/HTTP     │  ┌──────────────────────────────┐  │
  │  AI Agent│──────────────>│  │      Gateway (proxy)         │  │──> REST / GraphQL / gRPC
  │  or LLM  │               │  │      :7777                   │  │    Backend APIs
  └──────────┘               │  └──────────┬───────────────────┘  │
                             │             │ gRPC (EDS + GHS)      │
  ┌──────────┐  REST API     │  ┌──────────▼───────────────────┐  │
  │  CLI /   │──────────────>│  │    Control Plane (ctrl)      │──┼──> PostgreSQL
  │  Web UI  │               │  │    :5555                     │  │
  └──────────┘               │  └──────────────────────────────┘  │
                             └─────────────────────────────────────┘
```

- **Control Plane** (`control-plane/`) — Quarkus service on port `5555`. Manages services, expositions, organizations, users, gateways. Uses PostgreSQL + Flyway migrations + Hibernate ORM Panache with multi-tenant DISCRIMINATOR strategy. Hazelcast for caching.
- **Proxy/Gateway** (`proxy/`) — Quarkus service on port `7777`. Receives MCP requests, discovers expositions via gRPC from the control plane (`eds-v1.proto`, `ghs-v1.proto`), proxies calls to backend APIs. Supports REST, GraphQL, gRPC backends.
  - Clustering: multiple gateway instances form an Infinispan cluster via JGroups `DNS_PING`, replicating session, elicitation, and secret-resolution state so any gateway can handle any request. Controlled by the `reshapr.infinispan.stack` JVM property (`reshapr-local` for a single node, `reshapr-k8s` for a clustered setup).
  - Caching: doesn't round-trip to the control plane on every request. A `GatewayRegistry` holds raw exposition metadata and artifact content, populated at startup and refreshed on every change event. A `WorkCache` (Caffeine LRU) holds the parsed form of those artifacts, for example a parsed OpenAPI document, so re-parsing is avoided across calls.
- **CLI** (`cli/`) — TypeScript/Node.js CLI (`@reshapr/reshapr-cli`) built with Commander.js. Manages login, import, service lifecycle, and local Docker-based platform via `reshapr run`.
- **Web UI** (`web-ui/`) — SvelteKit 5 app (`@reshapr/reshapr-web-ui`, Svelte 5 runes + TailwindCSS 4 + bits-ui) deployed with `adapter-node` (SSR). Standalone, **not** a Maven module. Talks to the control-plane admin API server-side via `RESHAPR_ADMIN_API_KEY` (see `web-ui/src/lib/server/proxy.ts`, `auth.ts`). Runs on `5173` in dev (Vite), `3333` in the container.

Shared modules:
- `api/` — Protobuf definitions (`eds-v1.proto` for Exposition Discovery, `ghs-v1.proto` for Gateway Health)
- `commons/` — Shared Java utilities
- `mcp-commons/` — MCP protocol shared logic

**Domain concepts** (owned by the control plane):

| Concept | What it is |
|---|---|
| Service | A registered API (REST, GraphQL, or gRPC) with its main artifact (OpenAPI spec, GraphQL schema, or Protobuf descriptor) and any supplemental artifacts like output filters, custom tools, prompts, and resources. |
| Configuration Plan | A named set of rules for an exposition: which operations to include or exclude, the backend endpoint URL, authentication secrets, caching policy, header policy, OAuth2 config, and whether audit logging is on. |
| Exposition | The live pairing of a Service with a Configuration Plan, assigned to a Gateway Group. Each exposition gets a stable URL path on every gateway in the group. |
| Gateway Group | A named set of one or more gateways. Expositions are assigned to groups rather than individual gateways, which makes horizontal scaling and rollouts straightforward. |
| Secret | Encrypted credentials (Basic auth, bearer token, OAuth2 authorization-code, or OAuth2 client-credentials — optionally user-elicited) stored in the control plane and resolved by the gateway at request time. |

Secrets and configuration plans are encrypted at rest with AES-256/GCM. The keyset is rotatable via `RESHAPR_ENCRYPTION_KEYS_*`.

## Build & Dev Commands

```bash
# Full Maven build (Java 25 + preview features required)
./mvnw clean install -DskipTests

# Run control-plane in dev mode (starts PostgreSQL devservice automatically)
cd control-plane && ../mvnw quarkus:dev

# Run proxy in dev mode (requires control-plane running)
cd proxy && ../mvnw quarkus:dev

# CLI development
cd cli && npm install && npm run dev  # watch mode
npm link                               # makes `reshapr` binary available

# CLI tests
cd cli && npm test                     # unit tests (vitest)
cd cli && npm run test:e2e             # e2e tests

# Web UI development (requires control-plane reachable + RESHAPR_ADMIN_API_KEY in web-ui/.env)
cd web-ui && cp .env.example .env && npm install && npm run dev # Vite dev server on http://localhost:5173
cd web-ui && npm run check              # svelte-check type checking

# Native image build
./mvnw package -Pnative
```

## Key Conventions

- **Java 25 with `--enable-preview`** — all Java modules use preview features
- **MapStruct** for DTO mapping (annotation processor configured in compiler plugin)
- **Flyway migrations** in `control-plane/src/main/resources/db/migration/` — versioned as `V{major}.{minor}.{patch}__description.sql`
- **Quarkus profiles**: `%dev` auto-enables debug logging + dev data; `%prod` uses separate config; `%otel` for observability
- **Conventional Commits** required on PR titles: `feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:` (append `!` for breaking)
- **TSID** (Time-Sorted Identifiers) for entity IDs via `hypersistence-tsid`
- **Multi-tenancy** via Hibernate DISCRIMINATOR — entities extend `TenantAwareEntity`

## Project Structure Patterns

```
control-plane/src/main/java/io/reshapr/ctrl/
├── model/          # JPA entities (BaseEntity, TenantAwareEntity)
├── repository/     # Panache repositories
├── service/        # Business logic
├── rest/           # JAX-RS resources (v1/ for public API, admin/ for admin API)
├── security/       # JWT auth, token management
├── mcp/            # MCP protocol handling
└── config/         # Application configuration beans

proxy/src/main/java/io/reshapr/proxy/
├── proxy/          # Core proxying logic (REST/GraphQL/gRPC dispatch)
├── mcp/            # MCP server endpoint
├── registry/       # Service registry (synced from control-plane via gRPC)
├── security/       # Gateway token validation
└── audit/          # Request auditing
```

## Integration Points

- **Control Plane ↔ Proxy**: gRPC (Exposition Discovery Service + Gateway Health Service). Proxy authenticates with `RESHAPR_CTRL_TOKEN` (prefixed with org name).
- **Control Plane ↔ PostgreSQL**: JDBC, Flyway-managed schema
- **Proxy ↔ Backend APIs**: HTTP (REST/GraphQL) and gRPC, configurable timeouts via `reshapr.gateway.backend.http.default-timeout`
- **CLI ↔ Control Plane**: REST API on port 5555, authenticated via JWT tokens stored in `~/.reshapr/` config
- **Web UI ↔ Control Plane**: server-side calls to the admin API (`RESHAPR_CTRL_URL`, default `http://localhost:5555`) authenticated with `RESHAPR_ADMIN_API_KEY`. Add the UI to a local stack with `install/docker-compose-ui-addon.yml`.
  - Dev setup: `npm run dev`/`build` copy JSON schemas from `control-plane/src/main/resources/schemas`, so `web-ui/` must be run from a full monorepo checkout, not a standalone clone. `.env.example` ships with a working default `RESHAPR_ADMIN_API_KEY`; log in with `admin`/`password`.

## Request Flow (MCP `tools/call` against a REST backend)

```
MCP Client
    |  POST /mcp/{org}/{exposition-name}
    v
Gateway: McpController
    |  resolve exposition from GatewayRegistry (by ID or by org/name)
    |  validate MCP protocol headers and session
    v
ToolCallExecutor.execute()
    |  check whether backend secret requires elicitation (returns early if so)
    |  build McpToolConverter for the service type
    |  (OpenAPIMcpToolConverter / GraphQLMcpToolConverter / GrpcMcpToolConverter)
    v
McpToolConverter.getCallResponse()
    |  translate MCP tool params to backend protocol request
    v
ProxyService.callBackend()  (GrpcProxyService for gRPC)
    |  resolve backend secret via SecretReferenceResolver
    |  forward request to backend endpoint
    v
Backend API
    |  HTTP response
    v
ToolCallExecutor: ToolsOutputFiltersApplier (if output filter artifact is attached)
    |  apply field inclusion/exclusion rules (Context Control)
    v
MCP JSON response sent back to MCP Client
```

## Deployment Topologies

See [install/README.md](install/README.md) for the full list of Compose stacks (single control plane, all-in-one, two-gateway cluster) and addons (Web UI, OpenTelemetry). For Kubernetes, see [reshapr-helm-charts](https://github.com/reshaprio/reshapr-helm-charts) and [reshapr-controllers](https://github.com/reshaprio/reshapr-controllers).

## Testing

- Java: JUnit 5 + REST Assured + Quarkus `@QuarkusTest` (dev services auto-provision PostgreSQL)
- CLI: Vitest for unit tests; e2e tests in `cli/e2e/` require running platform (see `cli/e2e/global-setup.ts`)
- Docker Compose in `install/` for full local stack: `docker-compose-all-in-one.yml`

## OpenAPI Specs

Three API specifications in project root describe the public interfaces:
- `reshapr-public-openapi-v0.1.yaml` — public-facing API
- `reshapr-admin-ctrl-openapi-v0.1.yaml` — admin control plane API  
- `reshapr-authentication-openapi-v0.1.yaml` — authentication endpoints

