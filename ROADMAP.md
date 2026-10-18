# reShapr Roadmap

> **Last updated:** 2026-10-06 · **Current release:** 1.0.x ([releases](https://github.com/reshaprio/reshapr/releases))

This document describes where reShapr is heading over the next few minor releases. It is a
statement of **intent, not a commitment**: priorities may shift based on user feedback, the
evolution of the [Model Context Protocol](https://modelcontextprotocol.io/) specification, and
contributor availability. Items may be delivered differently than described, reordered, or
deferred.

For what has already shipped, see the [release notes](https://github.com/reshaprio/reshapr/releases).
For day-to-day tracking, see the [milestones](https://github.com/reshaprio/reshapr/milestones) and
[open issues](https://github.com/reshaprio/reshapr/issues).

## Guiding principles

Every item on this roadmap is evaluated against reShapr's core thesis:

1. **Fight Context Overload** — fewer, smaller, better-shaped payloads reach the LLM.
2. **No bespoke MCP server** — capabilities are configured through Services, Configuration Plans and
   Expositions, not by writing a bespoke MCP server.
3. **Client-agnostic** — anything the gateway does must work with *any* standard MCP client
   (Claude Desktop, Cursor, VS Code, custom agents) without a vendor SDK.
4. **Gateway-grade guarantees** — secrets, output filters, audit, tracing and rate controls apply
   uniformly, whatever the feature.
5. **Track the MCP spec** — prefer official primitives and extensions over proprietary ones;
   contribute upstream when a gap exists.

## Status legend

| Status | Meaning |
|---|---|
| 🧭 Exploring | Problem space being analysed; design notes or spikes may exist |
| 📐 Designing | Scope agreed, design document in progress or under review |
| 🚧 In progress | Actively being implemented |
| ✅ Shipped | Released; see release notes |

---

## 1.1 — MCP Code Mode

**Theme:** let the LLM *write code* against an exposition instead of emitting one tool call at a
time. 🧭 Exploring — see the [analysis & integration plan](plan-codeMode.md).

### Why

Code Mode attacks the same problem as reShapr from a complementary angle. Instead of exposing N
tools, the server exposes a tiny set of meta-tools (`search_tools`, `get_api_types`, `execute_code`)
plus a typed API generated from the service's schema. The model writes a script that chains
calls; intermediate results never transit through the model's context. Published figures report
order-of-magnitude token savings and better task completion.

reShapr already owns most of the required infrastructure through
[Scripted Custom Tools](docs/custom-tools-scripting.md) (QuickJS sandbox, `rs.*` host API,
allow-lists, timeouts, call-depth limits, audit, tracing). Code Mode is largely about opening that
runner to LLM-authored code, under control — and doing it **server-side**, so any MCP client
benefits without changes.

### Scope (tentative)

- New *tool exposure mode* per Configuration Plan: `tools` (current) · `code` · `hybrid`.
- `search_tools` — progressive disclosure over the exposition's operations (reuses the `WorkCache`).
- `get_api_types` — TypeScript declarations generated from OpenAPI / GraphQL / Protobuf.
- `execute_code` — runs LLM-authored JavaScript in the existing sandbox; every nested call goes through
  the normal pipeline (secrets, elicitation, output filters, audit).
- Sandbox hardening for untrusted code: CPU/memory budgets and interruption, code & result size
  limits, allow-list derived automatically from the plan's include/exclude rules.
- Elicitation strategy for scripts (pre-analysis or fail-and-retry).
- "Promote to Custom Tool": persist a working script as a Scripted Custom Tool.
- Web UI & CLI support for the new plan options.

### References

- Cloudflare — [*Code Mode: the better way to use MCP*](https://blog.cloudflare.com/code-mode/)
  (origin of the term) and the [Agents SDK Code Mode reference](https://developers.cloudflare.com/agents/api-reference/codemode/)
- Anthropic — [*Code execution with MCP*](https://www.anthropic.com/engineering/code-execution-with-mcp)
- Anthropic API — [Programmatic tool calling](https://platform.claude.com/docs/en/agents-and-tools/tool-use/programmatic-tool-calling)
- MCP roadmap — [Progressive discovery](https://modelcontextprotocol.io/development/roadmap#4-improved-primitives)
  (Core Primitives WG), which `search_tools` should converge with
- Research — [CodeAct: Executable Code Actions Elicit Better LLM Agents](https://arxiv.org/abs/2402.01030)

---

## 1.2 — Skills over MCP

**Theme:** serve [Agent Skills](https://agentskills.io/) directly from an exposition, so clients
can discover reusable, multi-step workflows alongside the tools they orchestrate. 🧭 Exploring.

### Why

Tools answer *"what can I call?"*; skills answer *"how should I use these calls to get the job
done?"*. The official `io.modelcontextprotocol/skills` extension
([SEP-2640](https://modelcontextprotocol.io/seps/2640-skills-extension), *Final*) standardises
discovery (`skills/list`, `skills/get`) and retrieval (via `resources/read`) of `SKILL.md`
bundles. Keeping workflow instructions next to the API they describe — and version-controlled in
the control plane — is a natural extension of reShapr's artifact model (prompts, resources, custom
tools).

### Scope (tentative)

- New supplemental artifact kind `Skills-v1alpha1`: a skill directory (`SKILL.md` + supporting
  files) attached to a Service, following the
  [Agent Skills specification](https://agentskills.io/specification).
- Gateway support for the Skills extension: capability declaration, `skills/list`, `skills/get`,
  file manifests with SHA-256 digests, `resources/read` and optional `resources/directory/read`.
- Skill visibility governed by the Configuration Plan, like tools and prompts.
- Caching metadata (`ttlMs`, `cacheScope`) aligned with the gateway's existing caching policy.
- Bridge with Code Mode (1.1): a skill may embed a reference script that is executed through
  `execute_code`, closing the loop "LLM discovers → admin freezes → skill documents".
- Import skills from the CLI (`reshapr import` or `reshapr attach`) and manage them in the Web UI.

### References

- MCP — [Skills extension overview](https://modelcontextprotocol.io/extensions/skills/overview)
  and [specification repository](https://github.com/modelcontextprotocol/ext-skills)
- MCP — [SEP-2640: Skills Extension](https://modelcontextprotocol.io/seps/2640-skills-extension)
- MCP — [Skills Over MCP Working Group charter](https://modelcontextprotocol.io/community/working-groups/skills-over-mcp)
- Agent Skills — [specification](https://agentskills.io/specification)
- Anthropic — [*Equipping agents for the real world with Agent Skills*](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- MCP — [client support matrix](https://modelcontextprotocol.io/extensions/client-matrix)

---

## 1.3 — MCP Events

**Theme:** let backends *push* to agents. Turn webhooks, message queues and long-running
operations into standard MCP notifications and tasks. 🧭 Exploring.

### Why

Today an agent learns about server-side change by polling or by holding a stream open. The MCP
[Triggers & Events Working Group](https://modelcontextprotocol.io/community/working-groups/triggers-events)
is specifying server-initiated events (channels, subscriptions, webhook-style callbacks) and the
2026-07-28 revision already ships `subscriptions/listen`. A gateway is the natural place to
normalise heterogeneous backend event sources into that model — with the same clustering,
secret-handling and audit guarantees as request/response traffic.

**Approach: AsyncAPI as the event-source contract.** reShapr describes synchronous backends
through their native contracts (OpenAPI, GraphQL schema, Protobuf descriptors). MCP Events will
most likely follow the same pattern by adding [AsyncAPI](https://www.asyncapi.com/) as a new
service type: an AsyncAPI document declares the channels, messages and protocol bindings (HTTP
webhooks, Kafka, AMQP, MQTT, WebSocket, …) of an event-driven backend, and the gateway maps them
onto MCP subscriptions and notifications — exactly as an OpenAPI document is mapped onto tools
today. This keeps the no-bespoke-server promise, reuses the import / Configuration Plan / output filter
machinery, and leaves the choice of transport adapter to the AsyncAPI bindings.

### Scope (tentative)

- **AsyncAPI service type**: import an AsyncAPI 3.x document as a Service main artifact
  (CLI, Web UI, admin API); parsed like other specs.
- Inbound event ingestion on the gateway driven by AsyncAPI bindings — HTTP webhook endpoints per
  exposition first (signature verification, replay protection), then broker adapters (Kafka,
  AMQP, MQTT, WebSocket) as follow-ups.
- Channel / message include-exclude rules in the Configuration Plan, mirroring operation rules.
- Mapping events to MCP primitives: `notifications/resources/updated` over `subscriptions/listen`,
  and task completion notifications for asynchronous backend operations
  ([Tasks extension](https://modelcontextprotocol.io/extensions/tasks/overview)).
- Adoption of the Triggers & Events extension as it stabilises (webhook/callback delivery,
  subscription lifecycle, ordering guarantees).
- Cluster-wide fan-out of events across gateway instances (Infinispan/JGroups), so a subscription
  held by any node receives events ingested by any other.
- Output filters applied to event payloads — Context Control for pushed data too.
- Configuration Plan options: which events are exposed, retention/replay window, audit.

### References

- AsyncAPI — [specification 3.0](https://www.asyncapi.com/docs/reference/specification/v3.0.0),
  [protocol bindings](https://github.com/asyncapi/bindings) and the
  [Java parser](https://github.com/asyncapi/jasyncapi)
- MCP — [Subscriptions (`subscriptions/listen`)](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/subscriptions)
- MCP — [Triggers & Events WG charter](https://modelcontextprotocol.io/community/working-groups/triggers-events)
  and [incubation repository](https://github.com/modelcontextprotocol/experimental-ext-triggers-events)
- MCP — [Tasks extension](https://modelcontextprotocol.io/extensions/tasks/overview),
  [SEP-1686](https://modelcontextprotocol.io/seps/1686-tasks) and
  [SEP-2663](https://modelcontextprotocol.io/seps/2663-tasks-extension)
- MCP roadmap — [Agentic Messaging Primitives](https://modelcontextprotocol.io/development/roadmap#1-agentic-messaging-primitives)
- Standards — [CloudEvents](https://cloudevents.io/) and
  [Standard Webhooks](https://www.standardwebhooks.com/) as candidate ingestion formats

---

## Continuous investments

Cross-cutting work that spans every release:

- **Spec tracking** — keep pace with MCP specification revisions and extensions
  ([changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)).
- **Security & identity** — follow the MCP
  [Agent Identity](https://modelcontextprotocol.io/development/roadmap#3-agent-identity-and-enterprise-ready-security)
  work (DPoP, token exchange, workload identity) for gateway authentication.
- **Observability** — richer OpenTelemetry signals for the new primitives (scripts, skills, events).
- **Developer experience** — CLI, Web UI and Helm chart parity for every feature above.
- **Docs & examples** — a design note under `docs/` and a worked example per theme.

## Beyond 1.3

Ideas under consideration, not yet scheduled:

- Progressive discovery as a first-class MCP primitive, once standardised.
- Semantic (embedding-based) `search_tools`.
- Policy engine for tool-call authorization (per user / per agent identity).
- Additional AsyncAPI bindings beyond those shipped in 1.3; other backend contracts (e.g. SOAP).

## How to influence this roadmap

- 💬 Open an [issue](https://github.com/reshaprio/reshapr/issues) to propose or debate a theme, or
  to make a concrete request — reference the roadmap item it relates to.
- 🛠 Want to build one of these? Read [CONTRIBUTING.md](CONTRIBUTING.md) and comment on the
  tracking issue before starting significant work.
- Decisions follow the process described in [GOVERNANCE.md](GOVERNANCE.md).
