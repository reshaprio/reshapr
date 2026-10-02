# Build and Development

This guide covers local setup and development for the reShapr monorepo. The Java services, CLI, and Web UI can be developed independently; the full platform requires Docker Compose.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| [Java](https://adoptium.net/) | 25 | Java modules use preview features. The Maven module configuration enables them where needed; the wrapper scripts do not set these flags. |
| [Node.js](https://nodejs.org/) | 22+ | Required for CLI and Web UI development. |
| [Docker](https://www.docker.com/) | Current stable, with Docker Compose V2 | Required to run the full local platform. |

## Build

Each Java module has its own Maven wrapper. Build and install them in dependency order from the repository root:

```bash
cd api && ./mvnw clean install -DskipTests && cd ..
cd commons && ./mvnw clean install -DskipTests && cd ..
cd control-plane && ./mvnw clean install -DskipTests && cd ..
cd proxy && ./mvnw clean install -DskipTests
```

## Develop

### Control Plane

Runs on port `5555`; Quarkus Dev Services starts PostgreSQL automatically.

```bash
cd control-plane && ./mvnw quarkus:dev
```

### Proxy / Gateway

Runs on port `7777` and requires the control plane to be running.

```bash
cd proxy && ./mvnw quarkus:dev
```

### CLI

```bash
cd cli && npm install && npm run dev
```

Run `npm link` from `cli/` to make the `reshapr` command available locally.

### Web UI

The Vite development server runs on port `5173`. The UI requires a reachable control plane and `RESHAPR_ADMIN_API_KEY` in `web-ui/.env`.

```bash
cd web-ui && cp .env.example .env && npm install && npm run dev
```

See [web-ui/README.md](web-ui/README.md) for environment variables, routing, Docker builds, and other UI-specific details.

## Run the Full Local Stack

Start the platform with the CLI:

```bash
reshapr run
```

Or use Docker Compose:

```bash
docker compose -f install/docker-compose-all-in-one.yml -f install/docker-compose-ui-addon.yml up
```

The control plane is available at `http://localhost:5555`. The local default credentials are `admin` / `password`.
See [install/README.md](install/README.md) for the available Compose stacks and addons.

## Test

### Java

```bash
cd control-plane && ./mvnw test && cd ..
cd proxy && ./mvnw test && cd ..
cd api && ./mvnw test && cd ..
```

Java tests use JUnit 5, REST Assured, and Quarkus `@QuarkusTest`. Quarkus Dev Services provisions PostgreSQL for tests.

### CLI

```bash
cd cli && npm test
cd cli && npm run test:e2e
```

CLI end-to-end tests require a running platform. See [cli/README.md](cli/README.md) for additional CLI development commands.

### Web UI

Run `npm run check` from `web-ui/` for Svelte type checking. See [web-ui/README.md](web-ui/README.md) for other UI checks.

### Benchmarks

Scripts in `benchmarks/` exercise REST, GraphQL, and gRPC proxy paths. They require a running platform; see the individual scripts for usage.

## Native Image

Build a native image from the Java module that provides the application:

```bash
cd control-plane && ./mvnw package -Pnative
```