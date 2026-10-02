# Contributing to reShapr

We love your input! We want to make contributing to this project as easy and transparent as possible.

## Summary of the contribution flow

The following is a summary of the ideal contribution flow. Please, note that Pull Requests can also be rejected by the maintainers when appropriate.

``` bash
    ┌───────────────────────┐
    │                       │
    │    Open an issue      │
    │  (a bug report or a   │
    │   feature request)    │
    │                       │
    └───────────────────────┘
               ⇩
    ┌───────────────────────┐
    │                       │
    │  Open a Pull Request  │
    │   (only after issue   │
    │     is approved)      │
    │                       │
    └───────────────────────┘
               ⇩
    ┌───────────────────────┐
    │                       │
    │   Your changes will   │
    │     be merged and     │
    │ published on the next │
    │        release        │
    │                       │
    └───────────────────────┘
```

## Code of Conduct

reShapr has adopted a Code of Conduct that we expect project participants to adhere to. Please [read the full text](CODE_OF_CONDUCT.md) so that you can understand what sort of behaviour is expected.

## Our Development Process

We use Github to host code, to track issues and feature requests, as well as accept pull requests.

## Technical Setup

### Prerequisites

| Requirement | Minimum Version | Notes |
|---|---|---|
| [Java](https://adoptium.net/) | 25 (with `--enable-preview`) | All Java modules require the `--enable-preview` flag. Each submodule has its own Maven Wrapper (`mvnw`) which handles this automatically. If you run Maven directly, pass `--enable-preview` to the JVM. |
| [Node.js](https://nodejs.org/) | 22+ | Required for CLI and Web UI development. |
| [Docker](https://www.docker.com/) + Docker Compose V2 | Latest stable | Required to run the full local platform stack. |

### Building and Running

This is a monorepo with 6 modules: 4 Maven modules (Java) and 2 standalone Node.js modules (CLI, Web UI). Not all modules depend on each other.

#### Full Maven Build

```bash
# Each module has its own Maven wrapper.
# Build in order: api → commons → control-plane → proxy
cd api && ./mvnw clean install -DskipTests && cd ..
cd commons && ../mvnw clean install -DskipTests && cd ..
cd control-plane && ../mvnw clean install -DskipTests && cd ..
cd proxy && ../mvnw clean install -DskipTests
```

This builds all Java modules (`api/` — protobuf definitions, `commons/` — shared Java utilities, `control-plane/`, `proxy/`) and installs them locally.

#### Individual Module Commands

**Control Plane** (port 5555):
```bash
cd control-plane && ../mvnw quarkus:dev
```

**Proxy / Gateway** (port 7777):
```bash
cd proxy && ../mvnw quarkus:dev
```

**CLI** (`@reshapr/reshapr-cli`):
```bash
cd cli && npm install && npm run dev   # watch mode
npm link                                # makes the `reshapr` binary globally available
```

**Web UI** (`@reshapr/reshapr-web-ui`):
```bash
cd web-ui && cp .env.example .env && npm install && npm run dev
```
See [web-ui/README.md](web-ui/README.md) for full developer documentation (setup, env vars, routing, Docker build, etc.).

### Running the Full Local Stack

The easiest way to run the complete platform locally (all services + Web UI):

```bash
# Using the CLI
reshapr run

# Or with Docker Compose directly
docker compose -f install/docker-compose-all-in-one.yml -f install/docker-compose-ui-addon.yml up
```

Connect to the control plane at `http://localhost:5555` with `admin`/`password`.

See [install/README.md](install/README.md) for all available compose stacks and addons.

### Running Tests

#### Java Tests

```bash
cd control-plane && ../mvnw test && cd ..
cd proxy && ../mvnw test && cd ..
cd api && ../mvnw test && cd ..
```

Java tests use JUnit 5 + REST Assured + Quarkus `@QuarkusTest`. Quarkus dev services auto-provision PostgreSQL for tests.

#### CLI Tests

```bash
cd cli && npm test                   # Unit tests (vitest)
cd cli && npm run test:e2e           # E2E tests (requires running platform)
```

See [cli/README.md](cli/README.md) for more CLI development commands.

#### Web UI Tests

See [web-ui/README.md](web-ui/README.md) for linting and type checking commands.

#### Benchmarks

The `benchmarks/` directory contains scripts for performance testing REST, GraphQL, and gRPC proxy paths (e.g., `run-e2e-rest-bench.sh`, `run-graphql-converter-bench.sh`). These require a running platform, same as CLI e2e tests. See individual scripts for usage.

## Issues

Open an issue in the repository you're contributing to **only** if you want to report a bug or a feature. Don't open issues for questions or support, instead join our [Discord `#support`](https://discord.gg/KyDUdam34h) channel and ask there.

## Bug Reports and Feature Requests

Please use our issues templates that provide you with hints on what information we need from you to help you out.

## Pull Requests

**Please, make sure you open an issue before starting with a Pull Request, unless it's a typo or a really obvious error.** Pull requests are the best way to propose changes to the specification. Take time to check the current working branch for the repository you want to contribute on before working :wink:

### AI Contribution Policy

If you use Generative AI tools (like GitHub Copilot, Cursor, etc.) to assist in your contributions, you must adhere to our [AI Contribution Policy](AI-POLICY.md). You are 100% accountable for your code, must explicitly disclose AI usage in your PR, and must not use AI tools to auto-reply to maintainers.

## Testing

New features and bug fixes should be accompanied by automated tests. Pull requests that add functionality without corresponding test coverage may be asked to add it before merging.

## Conventional commits

Our repositories follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/#summary) specification. Releasing to GitHub and NPM is done with the support of [semantic-release](https://semantic-release.gitbook.io/semantic-release/).

Pull requests should have a title that follows the specification, otherwise, merging is blocked. If you are not familiar with the specification simply ask maintainers to modify. You can also use this cheatsheet if you want:

- `fix:` prefix in the title indicates that PR is a bug fix and PATCH release must be triggered.
- `feat:` prefix in the title indicates that PR is a feature and MINOR release must be triggered.
- `docs:` prefix in the title indicates that PR is only related to the documentation and there is no need to trigger release.
- `chore:` prefix in the title indicates that PR is only related to cleanup in the project and there is no need to trigger release.
- `test:` prefix in the title indicates that PR is only related to tests and there is no need to trigger release.
- `refactor:` prefix in the title indicates that PR is only related to refactoring and there is no need to trigger release.

What about MAJOR release? just add `!` to the prefix, like `fix!:` or `refactor!:`

Prefix that follows specification is not enough though. Remember that the title must be clear and descriptive with usage of [imperative mood](https://chris.beams.io/posts/git-commit/#imperative).

Happy contributing :heart:

## License

When you submit changes, your submissions are understood to be under the same [Apache 2.0 License](LICENSE) that covers the project. Feel free to [contact the maintainers](MAINTAINERS.md) if that's a concern.

## References

This document was adapted from the open-source contribution guidelines for [Facebook's Draft](https://github.com/facebook/draft-js/blob/master/CONTRIBUTING.md).