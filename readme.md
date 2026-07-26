# archflow

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.0-green)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.18.0-brightgreen)](https://github.com/langchain4j/langchain4j)
[![i18n](https://img.shields.io/badge/i18n-PT--BR%20%7C%20EN-purple)](archflow-ui/src/i18n)

**Visual Java-Native Platform for AI Agent Workflows**

Build, visualize, and orchestrate AI agent workflows with a drag-and-drop designer and enterprise-grade Java backend.

</div>

<div align="center">

![archflow + LangChain4j](images/archflow_java_langchain4j.png)

</div>

<div align="center">

[Features](#features) | [Quickstart](#quickstart) | [Agent Patterns](#agent-patterns) | [Production](#production-checklist) | [Documentation](#documentation) | [Examples](#examples)

📖 **Documentação em Português**: [docs/readme.md](docs/readme.md) · [Visão Geral](docs/overview.md) · [Roadmap](docs/roadmap.md)

</div>

---

## Features

### Visual Workflow Designer

- **Web Component**: `<archflow-designer>` works in React, Vue, Angular, Svelte, or vanilla HTML
- **Drag-and-drop**: 15+ node types plus n8n-style annotations (sticky notes, group frames, section dividers)
- **Framework-agnostic**: Zero frontend lock-in via standard Web Components and Shadow DOM
- **Internationalised**: PT-BR (default) and EN out of the box; over 1,180 translation keys spanning every UI surface
- **npm distribution**: `npm install @archflow/component`

### Java-Native AI Engine

- **LangChain4j 1.18.0**: 15+ LLM providers (OpenAI, Anthropic, Google, Mistral, Ollama, and more)
- **MCP Protocol**: Model Context Protocol for standardized tool integration, with cleanup hooks for stdio servers
- **Agent Skills**: Load, activate, and manage behavioral instruction bundles ([agentskills.io](https://agentskills.io) spec) — now per-tenant scoped
- **Spring Boot 4.0.0**: REST API and auto-configuration layer (the agent core itself is framework-agnostic, built on pure Java + LangChain4j)
- **Suspend/Resume**: Conversational workflows with dynamic forms and human-in-the-loop

### Agent Patterns

archflow implements the industry-standard agent patterns validated by Anthropic, OpenAI, and Google:

| Pattern | Description | Use Case |
|---------|-------------|----------|
| **ReAct** | Thought-Action-Observation loop | General tool use, the default agentic pattern |
| **Plan-and-Execute** | Planner + Executor + Replanner | Multi-step tasks with cost efficiency |
| **ReWOO** | Plan all tool calls upfront with placeholders | Batch processing, predictable workflows |
| **Routing** | Semantic + LLM-based query routing | Multi-domain dispatch (customer support) |
| **Supervisor/Worker** | Orchestrator delegates to specialized agents | Multi-agent coordination |
| **Reflexion** | Self-critique with reflection memory | Tasks with clear success/failure signal |
| **CoT-SC** | Multi-path sampling with majority voting | High-accuracy reasoning |

### Agent Types

| Agent | Description |
|-------|-------------|
| **ConversationalAgent** | Customer service with intent classification, escalation policy, episodic memory |
| **ResearchAgent** | Multi-step research with task decomposition and action planning |
| **DataAnalysisAgent** | Text-to-SQL, schema introspection, statistical analysis |
| **MonitoringAgent** | Continuous metric collection, anomaly detection, alert dispatch |
| **SupervisorTemplate** | Workflow template with worker coordination and quality checking |

### Enterprise Ready

- **JWT auth filter**: Validates `Authorization: Bearer` headers via `JwtService`, populates user/role/tenant attributes, rejects 401 on protected paths. Disabled in dev, enforced in prod (`archflow.security.auth.enabled=true`).
- **Multi-tenant isolation**: BrainSentry, Linktor, and Skills configs are partitioned per tenant via `TenantContext` — one tenant's update cannot leak into another's slot.
- **Role-aware impersonation**: `X-Impersonate-Tenant` is honoured only when the property gate is on **and** the caller's JWT carries the `superadmin` role.
- **Bounded execution**: Parallel executor enforces per-step (5 min) and aggregate (10 min) timeouts; cancels pending futures on overrun to release worker threads and semaphore permits.
- **Plugin lifecycle**: `ArchflowPluginManager` is `AutoCloseable` — `unload(pluginId)` closes the per-plugin classloader so jar handles are released on hot reload.
- **Observability (current)**: in-memory execution trace store with FIFO eviction (exposed via the API), Spring Boot Actuator health endpoints, and structured logging with tenant MDC. OpenTelemetry/Prometheus/Grafana/Jaeger instrumentation lives in the `archflow-observability` library but is **not yet wired into the runtime** — see [Roadmap / experimental modules](#roadmap--experimental-modules).
- **Plugin Architecture**: plugins are trusted **fat-jars** loaded from a directory with a child-first classloader (full fallback to the application classloader) and SPI (`ServiceLoader`) discovery. There is no runtime dependency resolution and no sandbox — only load trusted jars.

### Standalone Export

Design workflows visually, then export them as **self-contained JARs** that run anywhere — no server, no database, no cloud required.

```
Design (browser) → Export (JSON) → Package (JAR ~19 MB) → Deploy (any machine with Java 25)
```

```bash
# Run a standalone workflow — only needs Java + LLM API key
export ARCHFLOW_API_KEY=sk-xxx
java -jar my-workflow.jar customer-support.json --input "Track order #123"
```

- **Zero infrastructure**: No Spring Boot, no PostgreSQL, no Redis
- **~19 MB JAR**: Includes flow engine, agent patterns, and LangChain4j runtime
- **CLI interface**: `--input`, `--var key=value`, `--timeout`, `--threads`, `--plugins`
- **Environment config**: `ARCHFLOW_API_KEY`, `ARCHFLOW_MODEL`, `ARCHFLOW_PROVIDER`

---

## Quickstart

### Docker Compose (full stack)

```bash
git clone https://github.com/edsonmartins/archflow.git
cd archflow
docker compose up
```

This starts the archflow server on `:8080`, PostgreSQL (with pgvector), and Redis. The default
profile is `dev` — demo seed data is loaded so the admin UI has something to render. See
[`RELEASE_NOTES_v1.md`](docs/development/RELEASE_NOTES_v1.md) for prod hardening.

### Build from source

Requires **JDK 25** and **Maven 3.8+**:

```bash
# Backend (211 tests in archflow-api alone, full suite ~700 tests)
mvn clean install

# Frontend
cd archflow-ui
npm install
npm run dev          # development server on :5173
npm run build        # production bundle (Vite lib + UMD)
npm run test:e2e     # 67 hermetic Playwright tests
```

### Embed the designer

```bash
npm install @archflow/component
```

```html
<archflow-designer
  workflow-id="customer-support"
  api-base="http://localhost:8080/api"
  theme="dark">
</archflow-designer>
```

See the [archflow-ui README](archflow-ui/README.md) for React, Vue, and Angular integrations.

---

## Architecture

![Architecture](docs/images/architecture.svg)

```
archflow/
├── archflow-model/                       # Domain models (Workflow, Flow, Step, Edge)
├── archflow-core/                        # Flow engine, JDBC state repo, audit log
├── archflow-agent/                       # Agent patterns (ReAct, Plan-and-Execute, ReWOO, CoT-SC)
│   ├── pattern/                          #   ReactAgentExecutor, PlanAndExecuteAgent, ReWOOExecutor
│   ├── handoff/                          #   AgentHandoff, AgentHandoffManager
│   └── routing/                          #   SemanticRouter (embedding + LLM hybrid)
├── archflow-events-proto/                # Event protocol shared between engine and UI
├── archflow-plugin-api/                  # Plugin SPI: catalog, metadata, lifecycle
├── archflow-plugin-loader/               # Classloader-isolated plugin loader (AutoCloseable)
├── archflow-langchain4j/                 # LangChain4j 1.18.0 integration
│   ├── archflow-langchain4j-openai/      #   OpenAI adapter
│   ├── archflow-langchain4j-anthropic/   #   Anthropic adapter
│   ├── archflow-langchain4j-mcp/         #   MCP Protocol client
│   ├── archflow-langchain4j-skills/      #   Agent Skills (SKILL.md loader + manager)
│   ├── archflow-langchain4j-realtime/    #   Realtime / voice adapters
│   └── archflow-langchain4j-provider-hub/#   Multi-LLM Hub (15+ providers)
├── archflow-brainsentry/                 # [experimental] Brain Sentry client library — not on the api runtime classpath yet
├── archflow-conversation/                # Suspend/resume; guardrails/governance/episodic memory are opt-in libraries (not wired to the server)
├── archflow-security/                    # JWT, RBAC, API keys, CORS
├── archflow-observability/               # [experimental] OTel/Micrometer classes — nothing instruments the runtime yet
├── archflow-performance/                 # [experimental] two-level cache library — orphan module, not integrated
├── archflow-templates/                   # Workflow templates (Customer Support, RAG, etc.)
├── archflow-marketplace/                 # Extension manifest catalog (RSA signature verification lacks trusted keys — checksum only in practice)
├── archflow-workflow-tool/               # Workflow-as-Tool pattern
├── archflow-standalone/                  # Export workflows as standalone JARs (no server)
├── archflow-plugins/                     # Built-in agents (Conversational, Research, etc.)
├── archflow-api/                         # Spring Boot REST + WebSocket layer
│   └── config/                           #   JwtAuthenticationFilter, ImpersonationFilter, TenantContext
├── archflow-ui/                          # React 19 + Vite + Mantine + Web Component designer
├── docs/                                 # PT-BR documentation, architecture diagrams
├── docs-site/                            # Docusaurus site
└── examples/                             # React, Vue, and Spring Boot REST-client demos (see examples/README.md)
```

---

## Agent Patterns

### ReAct (Reason + Act)

```java
ReactAgentExecutor executor = ReactAgentExecutor.builder()
    .reasoningFunction(ctx -> thinkAboutNextStep(ctx))
    .toolExecutor(action -> executeTool(action))
    .maxIterations(10)
    .timeout(Duration.ofSeconds(30))
    .build();

ReactResult result = executor.execute("What is the weather in Sao Paulo?");
```

### Semantic Router

```java
SemanticRouter router = SemanticRouter.builder()
    .embeddingFunction(text -> embedModel.embed(text))
    .addRoute("billing", "Payment, invoice, charges")
    .addRoute("technical", "Bug, error, crash")
    .confidenceThreshold(0.7)
    .strategy(RoutingStrategy.HYBRID) // semantic + LLM fallback
    .build();

RoutingResult result = router.route("My payment failed");
// result.getRouteName() => "billing"
```

### Agent Skills

```java
// Load skills from file system (SKILL.md with YAML front matter)
SkillsAdapter adapter = new SkillsAdapter();
adapter.configure(Map.of("skills.directory", "skills/"));

// List available skills
List<Map<String, String>> skills = adapter.execute("list_skills", null, context);
// => [{name: "docx", description: "Edit Word documents"}, ...]

// Activate a skill — returns full instructions for the LLM
Map<String, Object> skill = adapter.execute("activate_skill", "docx", context);
// => {name: "docx", content: "You are a document editor...", resources: [...]}
```

Skills are tracked **per tenant** via `TenantContext` — activating `docx` for tenant A
does not surface as active for tenant B.

### Agent Handoff

```java
AgentHandoffManager manager = new AgentHandoffManager();
manager.registerAgent("billing", "Billing Agent", Set.of("payments", "invoices"));
manager.registerAgent("support", "Tech Support", Set.of("bugs", "errors"));

AgentHandoff handoff = AgentHandoff.peer("support", "billing",
    Map.of("customerId", "12345"), "Customer needs billing help");
manager.executeHandoff(handoff);
```

### Standalone Export

```java
// Export any workflow to a standalone JSON file
FlowSerializer serializer = new FlowSerializer();
serializer.exportToFile(myFlow, Path.of("customer-support.json"));

// Later, run it anywhere without archflow server
StandaloneRunner runner = new StandaloneRunner();
FlowResult result = runner.run(new CliArgs(
    "customer-support.json", "Track my order #123",
    Map.of("customerId", "C-456"), 300, 4, null));
```

---

## Production Checklist

Before deploying to production, activate the `prod` Spring profile and supply these env vars:

| Variable | Required | Purpose |
|----------|----------|---------|
| `ARCHFLOW_JWT_SECRET` | yes | HS256 signing key (≥256 bits) |
| `SPRING_DATASOURCE_URL` | yes | PostgreSQL JDBC URL for `flow_states` |
| `SPRING_DATASOURCE_USERNAME` | yes | DB user |
| `SPRING_DATASOURCE_PASSWORD` | yes | DB password |
| `SPRING_DATA_REDIS_HOST` | optional | Redis host (defaults to `localhost`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | optional | OpenTelemetry collector endpoint |
| `ARCHFLOW_CORS_ORIGINS` | recommended | Comma-separated allowed origins |

The `prod` profile (`application-prod.yml`):

- Enables the JWT auth filter — unauthenticated calls to `/api/admin/*` return `401`.
- Sets `archflow.admin.seedDemoData=false` — no fixture tenants/users in responses.
- Empties `archflow.admin.tenantFallback` — missing `X-Tenant-Id` fails fast instead of collapsing into a shared workspace.
- Disables `X-Impersonate-Tenant` until a JWT with `superadmin` role is presented.

Full operational contract: [`docs/development/RELEASE_NOTES_v1.md`](docs/development/RELEASE_NOTES_v1.md).

### What is in-memory in v1

The following admin controllers store state in memory only — they reset on each redeploy.
This is intentional for v1; override the bean definitions in `ArchflowBeanConfiguration`
with JDBC implementations when persistence is required.

- `TenantControllerImpl` (tenant catalogue)
- `WorkspaceControllerImpl` (per-tenant users + scoped API keys)
- `BrainSentryConfigControllerImpl`, `LinktorConfigControllerImpl`, `SkillsControllerImpl` (per-tenant configs)
- `GlobalConfigControllerImpl` (LLM models, plan defaults, feature toggles — platform-wide, superadmin-only)
- `ScheduledTriggerControllerImpl` (Quartz job + cron registrations)

The execution-state path (`flow_states` table via `JdbcStateRepository`) **is** persisted —
your running workflows survive restarts.

---

## Roadmap / experimental modules

The modules below **compile and ship as libraries but are not wired into the server runtime yet**.
They are listed here so the feature list above stays honest (see `docs/PLANO_HOMOLOGACAO.md`,
decision 0.2: unwired features are unpublished from the docs until integrated).

| Module | What exists today | What is missing |
|--------|-------------------|-----------------|
| `archflow-observability` | OpenTelemetry/Micrometer helper classes, audit logging | Nothing instruments the runtime; no Prometheus/Grafana/Jaeger integration is active. Real observability today = API trace store + Actuator health |
| `archflow-brainsentry` | Brain Sentry client, interceptor, and `EpisodicMemory` adapter | Module is not on the `archflow-api` runtime classpath; per-tenant config screens exist but nothing consumes them at execution time |
| `archflow-performance` | Two-level cache (Caffeine + Redis) and pooling helpers | Orphan module — no other pom depends on it; LLM/embedding calls are not cached |
| `archflow-marketplace` | Extension **manifest catalog** with checksum verification | "Install" registers a manifest only (no code is loaded); RSA signature verification has no trusted keys configured, so it degrades to checksum. Not a marketplace yet |
| `archflow-conversation` (guardrails/governance/memory/summarizer) | `GuardrailChain`, `GovernanceResolver`, `EpisodicMemory`, `ConversationOrchestrator` as an opt-in library | Not called anywhere in the `archflow-api` execution path — you must wire them programmatically (see the module's `package-info`) |

---

## Documentation

- 📖 [docs/readme.md](docs/readme.md) — PT-BR documentation index
- 🏗 [Architecture](docs/architecture/architecture.md) — engine, plugins, adapters
- 🚀 [Quickstart](docs/development/quickstart.md) — first workflow in ~10 minutes
- 🛠 [Stack](docs/development/stack.md) — Java 25, Spring Boot 4.0.0, LangChain4j 1.18.0
- 📋 [Release notes (v1)](docs/development/RELEASE_NOTES_v1.md) — operations, profiles, env vars
- 🗺 [Roadmap](docs/roadmap.md) — what's next
- 🌐 [Docusaurus site](docs-site/) — built docs (work in progress)

---

## Examples

See [`examples/README.md`](examples/README.md) for the full status of each example.

| Example | What it covers | Status |
|---------|----------------|--------|
| **React** ([`examples/react/`](examples/react/)) | Designer web component + async agent invoke (`POST /archflow/agents/{id}/invoke`) + workflow execute | Demo — requires local build of `@archflow/component` (not published on npm) and a running backend |
| **Vue** ([`examples/vue/`](examples/vue/)) | Same as React, in Vue 3 | Demo — same caveats |
| **React Customer Support** ([`examples/react-customer-support/`](examples/react-customer-support/)) | REST client (JWT login, list/execute workflows, poll executions) + Mantine UI | Demo — real endpoints, requires local component build |
| **Spring Boot Integration** ([`examples/spring-boot-integration/`](examples/spring-boot-integration/)) | Spring Boot app consuming the archflow REST API via `WebClient` | Works against a running backend (REST only — no embedded engine) |

The former `examples/spring-boot/` demo was removed: it targeted a Java API that never existed
(`archflow-spring-boot-starter`, `br.com.archflow.core.FlowEngine`) and did not compile. To embed
archflow in a Spring Boot app today, consume the REST API as in `spring-boot-integration/`.

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 25, Spring Boot 4.0.0, Apache Camel 4.3.0 |
| **AI** | LangChain4j 1.18.0, MCP Protocol, Agent Skills |
| **Frontend** | React 19, TypeScript, Vite, Mantine UI, React Flow (@xyflow/react), react-i18next |
| **Databases** | PostgreSQL with pgvector, Redis |
| **Observability** | API trace store + Actuator health (today); OpenTelemetry/Prometheus/Grafana/Jaeger planned — library exists but is not wired in |
| **Testing** | JUnit 5 + Mockito + AssertJ (backend), Playwright + Vitest (frontend) |
| **Build** | Maven 3.8+, Node.js 18+, Docker |

---

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/my-feature`)
3. Run `mvn test` and `cd archflow-ui && npm run lint && npm run test:e2e` before pushing
4. Commit with [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`)
5. Open a Pull Request

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities and the
[Contribution Guide](docs/development/contributing.md) for code style and review
expectations (Google Java Style, 4-space indent, 120-char lines).

---

## License

[Apache License 2.0](LICENSE)

---

<div align="center">

**Built with LangChain4j** · [GitHub](https://github.com/edsonmartins/archflow) · [Issues](https://github.com/edsonmartins/archflow/issues) · [Documentação PT-BR](docs/readme.md)

</div>
