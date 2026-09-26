# Local Install

This directory has Docker Compose files and helper scripts to run reShapr locally. This page explains when to use each file and how to combine them.

## Prerequisites

- **Docker & Docker Compose V2** must be installed and running.
- **Write access to `~/tmp`**: PostgreSQL data will be persisted to `~/tmp/reshapr-data`. Ensure this directory is writable.
- **No authentication required**: The Compose files pull public `:nightly` (latest dev) builds from `registry.reshapr.io` without requiring any Docker login.

All commands and scripts below assume you're running them from inside this `install/` directory.

## Quick Start

The easiest way to get the full platform running with the Web UI is:

```bash
docker compose -f docker-compose-all-in-one.yml -f docker-compose-ui-addon.yml up
```

Once started, open [http://localhost:3333](http://localhost:3333) in your browser and log in with the default credentials:
- **Username**: `admin`
- **Password**: `password`

The control plane creates this admin user automatically on startup — no need to run `create-admin.sh`.

### Verifying & Next Steps

You can verify the control plane is healthy via:
```bash
curl -f http://localhost:5555/q/health/ready
```

Once running, head over to `cli/README.md` to learn how to log in with the CLI and interact with the platform.

### Teardown

To stop the platform and remove the containers, you **must use the exact same `-f` flags** you used to start it. Otherwise, Compose won't know about the addon services.

```bash
# Example teardown for the Quick Start command above
docker compose -f docker-compose-all-in-one.yml -f docker-compose-ui-addon.yml down
```

## Compose files

| File | What it starts | Use it when |
|---|---|---|
| [`docker-compose.yml`](docker-compose.yml) | PostgreSQL + control plane only | You want to run the gateway separately, e.g. via [`start-proxy.sh`](start-proxy.sh) or your own build |
| [`docker-compose-all-in-one.yml`](docker-compose-all-in-one.yml) | PostgreSQL + control plane + a single gateway | You want the full platform running with one command |
| [`docker-compose-cluster.yml`](docker-compose-cluster.yml) | PostgreSQL + control plane + two gateways | You want to see MCP session/elicitation state replicate across gateways (Infinispan + JGroups `DNS_PING`), the same mechanism used in Kubernetes |

Only one of these three should be run at a time — they all define a `control-plane` service and will conflict on ports (`5555`, `54321`) if started together.

```bash
# Control plane only (bring your own gateway)
docker compose -f docker-compose.yml up

# Full stack, single gateway
docker compose -f docker-compose-all-in-one.yml up

# Full stack, two-gateway cluster demo
docker compose -f docker-compose-cluster.yml up
```

### Addon files

These layer extra services onto one of the stacks above with `-f`. They don't work standalone.

| File | Adds | Compatible with |
|---|---|---|
| [`docker-compose-ui-addon.yml`](docker-compose-ui-addon.yml) | Web UI on port `3333` (access at `http://localhost:3333` with `admin`/`password`) | Any of the three stacks above |
| [`docker-compose-otel-addon.yml`](docker-compose-otel-addon.yml) | Grafana/Tempo/Loki collector (`otel-lgtm`) on port `4444`, and re-enables OpenTelemetry export on `gateway-01` | [`docker-compose-all-in-one.yml`](docker-compose-all-in-one.yml) |

It also merges onto [`docker-compose-cluster.yml`](docker-compose-cluster.yml), but only instruments `gateway-01` there, not `gateway-02` — stick with all-in-one for full coverage.

Combine addons by passing multiple `-f` flags, base stack first:

```bash
# All-in-one + Web UI
docker compose -f docker-compose-all-in-one.yml -f docker-compose-ui-addon.yml up

# All-in-one + Web UI + OpenTelemetry
docker compose -f docker-compose-all-in-one.yml -f docker-compose-ui-addon.yml -f docker-compose-otel-addon.yml up
```

## Helper scripts

| Script | Purpose |
|---|---|
| [`start-control-plane.sh`](start-control-plane.sh) | Shortcut for `docker compose up` (control plane only) |
| [`start-all.sh`](start-all.sh) | Shortcut for `docker compose -f docker-compose-all-in-one.yml up` |
| [`start-proxy.sh`](start-proxy.sh) | Runs a single standalone gateway container against a control plane on `host.docker.internal:5555` — use alongside `docker-compose.yml` |
| [`create-admin.sh`](create-admin.sh) | Creates the initial admin user — the Compose files do this automatically, so only use this script if you're running without the default env vars |
| [`create-user+org.sh`](create-user+org.sh) | Creates a regular user, a new organization for them, and default quotas |
| [`create-service-account.sh`](create-service-account.sh) | Creates a service account (used by Kubernetes controllers/operators) |
| [`update-service-account.sh`](update-service-account.sh) | Updates an existing service account by ID |

All scripts assume the control plane is reachable at `http://localhost:5555`.

### Authentication

- `create-admin.sh`, `create-user+org.sh`, `create-service-account.sh`, and `update-service-account.sh` authenticate with the `x-reshapr-api-key` header, using the control plane's built-in default admin API key. None of these Compose files set it explicitly. Edit the `SERVER_TOKEN` / `SERVER_URL` variables at the top of each script if you've overridden `RESHAPR_CTRL_API_KEY`.
- `start-proxy.sh` uses a different secret, `RESHAPR_DEFAULT_GATEWAY_TOKENS` (defined in the Compose files), to authenticate the gateway to the control plane.

## Notes

- Default control-plane credentials (`RESHAPR_ADMIN_NAME` / `RESHAPR_ADMIN_PASSWORD`) and secrets (`RESHAPR_ENCRYPTION_KEYS_V1`, `RESHAPR_DEFAULT_GATEWAY_TOKENS`) in these files are for local development only — change them for anything beyond your own machine.
- PostgreSQL data persists to `~/tmp/reshapr-data` across restarts. Delete that directory for a clean slate.
- For Kubernetes deployments, see [reshapr-helm-charts](https://github.com/reshaprio/reshapr-helm-charts).
