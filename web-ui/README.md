# reShapr Web UI

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Framework | [SvelteKit](https://kit.svelte.dev/) | 2.70.2 |
| Runtime | [Svelte](https://svelte.dev/) (v5 runes) | 5.55.9 |
| Styling | [TailwindCSS](https://tailwindcss.com/) + [tailwind-variants](https://tailwind-variants.org/) | 4.3.0 |
| Components | [bits-ui](https://www.bits-ui.com/) (headless primitives) | 2.18.1 |
| Icons | [Lucide](https://lucide.dev/) + [Hugeicons](https://hugeicons.com/) | 1.17.0 / 4.2.0 |
| Editor | [Monaco Editor](https://microsoft.github.io/monaco-editor/) + [monaco-yaml](https://github.com/monaco-yaml/monaco-yaml) | 0.55.1 / 5.5.1 |
| Deployment | [@sveltejs/adapter-node](https://github.com/sveltejs/kit/tree/main/packages/adapter-node) (SSR, Node.js) | 5.5.4 |

## Quick Start

### Prerequisites

- **Node.js** 22+ (production Docker image uses UBI9 with Node 22)
- **Docker** (if running the full local stack)
- A running [reShapr control plane](../install/README.md) (default `http://localhost:5555`)

### Setup

```bash
# 1. Clone the full monorepo (web-ui cannot be run standalone)
git clone https://github.com/reshaprio/reshapr.git
cd reshapr/web-ui

# 2. Copy .env.example to .env and adjust if needed
cp .env.example .env

# 3. Install dependencies
npm install

# 4. Start the dev server (Vite on port 5173)
npm run dev
```

> [!IMPORTANT]
> The build/dev scripts run `scripts/copy-schemas.mjs` to copy JSON schemas from
> `control-plane/src/main/resources/schemas` into `src/lib/schemas/`.
> **You must have the full monorepo checked out** — a standalone `web-ui/` clone will fail at build time.
> The script will throw if schemas are missing from both source and destination. If you've run the build once and schemas exist in `src/lib/schemas/`, the script will warn but continue when the source is missing (this is how CI works when building the web-ui in isolation).

### Default Credentials

The `.env.example` ships with a pre-configured `RESHAPR_ADMIN_API_KEY` that matches the default admin user created by the local Docker stack. Log in with:

- **Username:** `admin`
- **Password:** `password`

## Configuration

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `RESHAPR_CTRL_URL` | `http://localhost:5555` | Control plane API base URL (no trailing slash). Commented out by default — only needed if your control plane runs at a different URL. |
| `RESHAPR_ADMIN_API_KEY` | *(required)* | Admin API key for server-side calls to the control plane. Set from `.env.example` for local dev. |
| `RESHAPR_WEBUI_PUBLIC_URL` | `http://localhost:5173` | Public URL of this web-ui (used for OIDC `redirect_uri`). Only needed when behind a reverse proxy or when the browser accesses the UI at a different URL than the dev server. |
| `RESHAPR_CTRL_PUBLIC_URL` | *(falls back to `RESHAPR_CTRL_URL`)* | Public control-plane URL for browser redirects (OIDC). |

### Connecting to a Local Control Plane

1. Start the local stack: `docker compose -f ../install/docker-compose-all-in-one.yml -f ../install/docker-compose-ui-addon.yml up`
2. Copy `.env.example` to `.env` (already configured with the default admin key).
3. Run `npm run dev` — the UI will proxy requests to the control plane server-side.

### Connecting to a Remote Control Plane

Update `.env`:

```env
RESHAPR_CTRL_URL=https://your-control-plane.example.com
RESHAPR_ADMIN_API_KEY=your-admin-api-key-here
```

## Project Structure

```
web-ui/
├── scripts/
│   └── copy-schemas.mjs          # Pre-build: copies control-plane JSON schemas
├── src/
│   ├── lib/
│   │   ├── api/                  # REST client for control-plane v1 APIs
│   │   │   ├── client.ts         # apiClient() — typed REST methods
│   │   │   ├── config.ts         # Configuration API methods
│   │   │   ├── errors.ts         # ApiError class
│   │   │   └── session-guard.ts  # Auth guard for server-side routes
│   │   ├── artifacts/            # Artifact editing & parsing utilities
│   │   ├── exposition/           # Exposition-related helpers
│   │   ├── plan/                 # Configuration plan helpers
│   │   ├── monaco/              # Monaco editor setup, YAML worker, themes
│   │   ├── schemas/              # Generated JSON schemas (from control-plane)
│   │   ├── server/               # Server-side logic (SvelteKit hooks)
│   │   │   ├── auth.ts           # Session cookie management, JWT helpers
│   │   │   └── proxy.ts          # Request proxying to control plane
│   │   ├── stores/               # Reactive Svelte 5 stores
│   │   │   ├── auth.svelte.ts    # Auth state (user, org, loading)
│   │   │   ├── sidebar.svelte.ts # Sidebar state
│   │   │   └── theme.svelte.ts   # Theme state (light/dark)
│   │   ├── types.ts              # TypeScript interfaces (User, UserProfile, etc.)
│   │   ├── serviceHub.ts         # Service record parsing
│   │   ├── dashboardStats.ts     # Dashboard statistics helpers
│   │   ├── format-api-error.ts   # Error formatting utility
│   │   ├── gravatar.ts           # Gravatar URL generation
│   │   ├── operationsList.ts     # Operations list helpers
│   │   └── utils.ts              # General utilities
│   │   └── components/           # Page-level Svelte components
│   │       ├── artifacts/        # Artifact editor components
│   │       ├── exposition/       # Exposition components
│   │       ├── plan/             # Plan editor components
│   │       └── ui/               # bits-ui primitives (19 headless components)
│   ├── routes/
│   │   ├── login/                # Login page
│   │   ├── (app)/                # Authenticated app layout
│   │   │   ├── +page.svelte      # Dashboard
│   │   │   ├── services/         # Services listing & detail
│   │   │   ├── expositions/      # Expositions listing & detail
│   │   │   ├── gateways/         # Gateways listing
│   │   │   ├── gateway-groups/   # Gateway groups
│   │   │   ├── secrets/          # Secrets management
│   │   │   ├── organization/     # Organization settings
│   │   │   ├── account/          # Account settings
│   │   │   └── admin/            # Admin pages (organizations, quotas)
│   │   └── api/                  # Server-side API routes
│   │       ├── auth/             # Login, logout, OIDC, session
│   │       ├── config/           # App config endpoint
│   │       ├── v1/[...path]/     # Proxy to control-plane v1 API
│   │       └── admin/[...path]/  # Proxy to control-plane admin API
│   ├── app.css                   # Global styles
│   ├── app.html                  # HTML template
│   └── app.d.ts                  # Type declarations
├── static/                       # Static assets
├── .env.example                  # Default environment variables
├── Dockerfile                    # Multi-stage Docker build
├── svelte.config.js              # SvelteKit + adapter-node config
├── vite.config.ts                # Vite config
└── tsconfig.json                 # TypeScript config
```

## Key Concepts

### API Client

`src/lib/api/client.ts` exports `apiClient()` — a typed REST client that calls the web-ui's own server-side API routes (`/api/v1/...`, `/api/admin/...`). These routes proxy requests to the control plane server-side, so **no Bearer tokens are exposed to the browser**. The JWT lives in an `httpOnly` cookie set by the auth server routes.

### Error Handling

`src/lib/api/errors.ts` defines the `ApiError` class used by the API client when requests fail. `src/lib/format-api-error.ts` formats error responses for display (tries JSON `message`/`error` fields, falls back to raw text or HTTP status text). Server-side routes are guarded by `src/lib/api/session-guard.ts`, which blocks unauthenticated access before reaching any backend call.

### Monaco Editor

`src/lib/monaco/` sets up the Monaco editor used for artifact editing: `setup.ts` configures the editor instance, `yaml.worker.ts` provides a Web Worker for YAML language support, `schemas.ts` loads JSON schemas for validation, and `theme.ts` applies the editor color theme.

### Page-specific Components

Under `src/lib/components/` there are three subdirectories matching the main pages: `artifacts/` (artifact editor UI), `exposition/` (exposition listing and detail), and `plan/` (configuration plan editing). These import headless bits-ui primitives and add application-specific styling.

### Authentication Flow

1. User logs in via `POST /api/auth/login` (or OIDC)
2. Server validates credentials against the control plane
3. Server sets an `httpOnly` cookie (`reshapr-session`) containing the JWT
4. Subsequent requests automatically include the cookie (browser sends it; server routes validate it)
5. The auth store (`src/lib/stores/auth.svelte.ts`) holds the decoded user profile (not the token) using Svelte 5 `$state` runes

### Proxy Architecture

The web-ui acts as a **backend-for-frontend (BFF)**:
- Browser → `/api/v1/[...path]` or `/api/admin/[...path]` (SvelteKit server routes)
- Server → Control plane (server-side `fetch`, authenticated with `RESHAPR_ADMIN_API_KEY`)
- The server copies method, body, content-type, and query string. Path traversal is sanitized.

### Routing

| Route Pattern | Page | Description |
|---|---|---|
| `/login` | Login | Login form (Reshapr auth or OIDC) |
| `/` | Dashboard | Overview with stats, recent services (under `(app)/` layout group) |
| `/services` | Services List | Paginated list of all services |
| `/services/[id]` | Service Detail | Single service view |
| `/services/[id]/expositions` | Expositions | List of expositions for a service |
| `/services/[id]/artifacts` | Artifacts | List of artifacts (OpenAPI, GraphQL, gRPC) |
| `/services/[id]/artifacts/[artifactId]` | Artifact Editor | Monaco editor for editing artifacts |
| `/services/[id]/plans` | Plans | Configuration plans for a service |
| `/services/[id]/plans/[planId]` | Plan Detail | Single plan view |
| `/services/[id]/plans/new` | New Plan | Create a new configuration plan |
| `/expositions` | Expositions List | All expositions across services |
| `/expositions/[id]` | Exposition Detail | Single exposition view |
| `/gateways` | Gateways | Gateway instances |
| `/gateway-groups` | Gateway Groups | Gateway group management |
| `/secrets` | Secrets | Secret management (Basic, Bearer, OAuth2) |
| `/organization` | Organization | Organization settings |
| `/account` | Account | User account settings |
| `/admin/organizations` | Admin Orgs | Admin: manage organizations |
| `/admin/quotas` | Admin Quotas | Admin: manage quotas |

## Development

### Available Scripts

```bash
# Copy schemas from control-plane (runs automatically before dev/build/check)
npm run copy-schemas

# Start dev server with HMR (port 5173)
npm run dev

# Build for production (outputs to build/)
npm run build

# Preview production build locally
npm run preview

# Type check (svelte-check)
npm run check

# Type check in watch mode
npm run check:watch

# Lint (ESLint)
npm run lint
```

### Building for Production

```bash
npm run build
```

This produces a Node.js application in `build/` using `@sveltejs/adapter-node`. Deploy with:

```bash
node build
```

### Docker Build

```bash
docker build -t reshapr-web-ui:dev .
```

The Dockerfile uses a multi-stage build:
1. **Build stage** (Node 25 Alpine) — installs dependencies, runs `npm run build`
2. **Production stage** (Red Hat UBI9 minimal) — installs Node 22 via microdnf, copies build artifacts

Production image exposes port **3333**.

## State Management

The app uses **Svelte 5 runes** (`$state`, `$derived`, `$effect`) for reactive state. There are three stores:

| Store | File | Purpose |
|---|---|---|
| Auth | `stores/auth.svelte.ts` | User profile, auth mode (reshapr/oidc), loading state, derived flags (isAuthenticated, isAdmin, currentOrg, hasMultipleOrgs, isOwnerOfCurrentOrg) |
| Sidebar | `stores/sidebar.svelte.ts` | Sidebar open/closed state |
| Theme | `stores/theme.svelte.ts` | Light/dark mode preference |

The JWT token itself is **never stored in client-side state** — it lives in an `httpOnly` cookie. The auth store only holds the decoded user profile.

## UI Components

The UI is built with **bits-ui** (headless, accessible primitives) wrapped in custom styled components:

| bits-ui Primitive | Used For |
|---|---|
| Alert | Error/warning notifications |
| Badge | Service type, status indicators |
| Button | Actions throughout the app |
| Card | Content containers |
| Checkbox | Boolean toggles |
| Collapsible | Expandable sections |
| Dialog | Confirm dialogs, modals |
| Dropdown Menu | Action menus |
| Input | Text fields |
| Label | Form labels |
| Progress | Quota gauges |
| Select | Dropdown selects |
| Separator | Visual dividers |
| Sheet | Side panels |
| Switch | Toggle switches |
| Table | Data tables |
| Tabs | Tabbed interfaces |
| Textarea | Multi-line text |
| Tooltip | Hover hints |

Icons come from **Lucide** (general purpose) and **Hugeicons** (domain-specific).

## Contributing

1. Follow the [main contributing guide](../CONTRIBUTING.md).
2. Prefix PR titles with `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, or `chore:` (Conventional Commits).
3. Run `npm run check` to verify types before submitting.
4. Run `npm run lint` to fix linting issues.