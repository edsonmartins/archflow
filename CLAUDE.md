# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

archflow is an open-source Java framework for AI agent automation, built on top of LangChain4j. It provides structured workflow development, execution, and management with a plugin architecture and visual designer.

## Build and Development Commands

### Backend (Java/Maven)
```bash
# Build entire project (including tests)
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ClassName

# Run specific test method
mvn test -Dtest=ClassName#methodName

# Generate coverage report
mvn jacoco:report

# Run with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Frontend (React/TypeScript)
```bash
cd archflow-ui

# Install dependencies
npm install

# Development server
npm run dev

# Build for production
npm run build

# Lint code
npm run lint

# Preview production build
npm run preview
```

## Module Architecture

The project follows a multi-module Maven structure. Key modules and their relationships:

### Core Layer
- **archflow-model** - Domain models (Flow, FlowStep, FlowConfiguration, ExecutionContext)
- **archflow-core** - Execution engine, Flow Engine, Execution Manager, State Manager
- **archflow-api** - Public API contracts

### Agent Layer
- **archflow-agent** - Main `ArchFlowAgent` entry point, metrics collection, plugin orchestration

### Plugin System
- **archflow-plugin-api** - Plugin catalog and development interfaces
- **archflow-plugin-loader** - Loads plugin fat-jars from a directory with a child-first classloader (full fallback to the parent). No runtime dependency resolution, no sandbox — only trusted jars.
- **archflow-plugins** - Pre-built implementations:
  - `archflow-plugin-assistants` - AI assistant implementations
  - `archflow-plugin-agents` - AI agent implementations
  - `archflow-plugin-tools` - Tool implementations

### LangChain4j Integration
The **archflow-langchain4j** module contains multiple submodules following an Apache Camel-style adapter pattern:
- `archflow-langchain4j-core` - Base adapter interfaces (SPI pattern)
- `archflow-langchain4j-openai` - OpenAI integration
- `archflow-langchain4j-anthropic` - Anthropic integration
- `archflow-langchain4j-memory-*` - Memory backends (Redis, JDBC, etc.)
- `archflow-langchain4j-vectorstore-*` - Vector stores (Pinecone, pgvector, Redis)

Adapters are discovered via SPI at runtime.

### Server / Protocol
- **archflow-api** (module) - Spring Boot REST + WebSocket server (controllers, filters, runtime wiring)
- **archflow-events-proto** - Event protocol shared between engine and UI
- **archflow-security** - JWT, RBAC, API keys, CORS (used by archflow-api)
- **archflow-workflow-tool** - Workflow-as-Tool pattern
- **archflow-templates** - Built-in workflow templates (registered via SPI)
- **archflow-standalone** - Export/run workflows as standalone JARs (CLI, no server)
- **archflow-dsl** - Java DSL for authoring workflows in code, emitting the canonical document (JSON/YAML). Depends only on `archflow-model` + Jackson — no Spring, no engine
- **archflow-sdk-java** - `ArchflowClient` (REST) and `EmbeddedWorkflowRunner` (in-process). The `archflow-standalone` dependency is `optional`: only the embedded runner needs it, and it drags the whole engine along
- **archflow-conversation** - Suspend/resume wired to the server; guardrails/governance/episodic memory/summarizer are an OPT-IN library not yet called by the archflow-api execution path

### Two LLM integration layers — know which one you are in

- **`archflow-langchain4j-provider-hub`** (`LLMConfigResolver` → `LLMProviderHub` → `ChatModel`) —
  used by `McpAgentRunner`, `AgUiAgentController` and `DynamicWorkflowService`. This is the path for
  **agent loops**.
- **The `LangChainAdapter` SPI layer** (`archflow-langchain4j-{openai,anthropic,memory-*,
  vectorstore-*,chain-rag,...}`) — used by **workflow nodes**, through the bridge below.

Until recently the adapter layer had **no invoker at all**: `createAdapter` was never called in
production and a node naming a provider failed with "component not found", because an adapter is not
an `AIComponent`. `LangChainAdapterComponent` (`br.com.archflow.api.flow.adapter`) is the bridge —
the two interfaces are nearly identical, so it is thin. Things to keep in mind when touching it:

- **Routing is conservative.** `AdapterNodeTypes.isAdapterNode` only routes when the registry really
  has that provider for that node type; anything else falls through to the `ComponentCatalog`, so
  existing workflows are unaffected.
- **Creation is lazy.** The adapter factory calls `configure` → `validate`, which requires the API
  key. Building at deserialization time would make merely opening a workflow in the designer throw.
- **The adapter cache is per tenant.** The key comes from `TenantKeyResolver` and the tenant is only
  known at execute time; one shared configured adapter would hand one tenant's key to another.
- **Secrets do not live in the workflow JSON.** The resolver wins over an inline `apiKey` left in a
  node — governance must not be overridable by a forgotten field.
- **`MemoryRestorer` is still `null` on purpose.** Chat memory is read by the chat adapters, but
  nothing *writes* it during a flow yet. A restorer would repopulate a store nobody fills.

### Experimental / not wired to the runtime (honest state — see docs/PLANO_HOMOLOGACAO.md)
- **archflow-brainsentry** - Brain Sentry client library; not on the archflow-api runtime classpath
- **archflow-observability** - OTel/Micrometer classes exist but nothing instruments the runtime; only the audit trail (`AuditRepository`) is consumed. Real observability today: flow/step metrics from `MetricsCollector` (shared with `ObservabilityService`), the API trace store fed by `TraceStoreRecorder`, and Actuator health. There are no OTel spans and no per-tool-call latency/token accounting
- **archflow-performance** - Two-level cache library; orphan module, no pom depends on it
- **archflow-marketplace** - Extension manifest catalog; "install" registers a manifest only, RSA signature verification has no trusted keys (checksum in practice)

### Frontend
- **archflow-ui** - React + TypeScript + Vite, uses Mantine UI and React Flow for visual workflow designer

### The workflow document has three readers — keep them in agreement

The stored workflow JSON (`{id, metadata, configuration, steps[]}`) is parsed by
`DefaultFlowStepFactory` (executes it), carried by the `Serializable*` POJOs of `archflow-standalone`
(YAML bridge + standalone execution), and now written by `archflow-dsl`. Nothing in the compiler
forces them to agree, so two tests do: `DslConformanceTest` runs DSL output through the *real*
deserializer, and `AdapterNodeTypesMirrorTest` fails in both directions if the node-type lists drift.

Two traps that already bit:

- **Edges live inside each step** (`steps[].connections`), which is where the engine reads them. The
  UI templates in `archflow-ui/public/templates` carry a *top-level* `connections` array — a different
  format. Emitting that one gives a flow that opens fine in the designer and doesn't walk when executed.
- **`type` has two dialects.** The factory treats it as a free string (`"llm-chat"`, `"input"` — what
  the designer writes); `SerializableStep` used to declare it as the `StepType` enum, so *every*
  designer-authored flow failed `GET /api/workflows/{id}/yaml` and couldn't run standalone. Only
  hand-written flows using enum names worked — including the test fixtures, which is why nothing
  caught it. `SerializableStep` now keeps the raw string for transport and derives `StepType` (`TOOL`
  when it isn't an enum name, matching what the engine assigns). Do not "simplify" it back to an enum
  field, and do not let the raw type be rewritten on output — adapter routing matches on the node type.

## Key Architectural Patterns

1. **Adapter Pattern** - LangChain4j integrations use Apache Camel-style adapters for extensibility
2. **Plugin Architecture** - Plugins are fat-jars loaded from a directory via `ServiceLoader`, isolated by a child-first classloader with full fallback to the application classloader
3. **Flow-Based Processing** - Declarative workflow definitions with `Flow`, `FlowStep`, and `FlowConfiguration`
4. **SPI (Service Provider Interface)** - Used for adapter discovery in archflow-langchain4j
5. **State Management** - Distributed state management for flow execution contexts

## Code Style

- **Google Java Style Guide**
- 4 spaces for indentation
- 120 character line limit
- UTF-8 encoding
- Conventional Commits for commit messages (`feat:`, `fix:`, `docs:`, `test:`)

## Testing

- **JUnit 5** for unit tests
- **Mockito** for mocking
- **AssertJ** for assertions
- **JaCoCo** for coverage, with a **ratchet gate**, not an aspirational one. Real coverage ranges
  from ~38% to ~98% per module, so a flat 80% rule would fail the build immediately. Each module
  declares `jacoco.min.coverage` (global default `0.30`, higher in the stronger modules) and the
  `check` goal enforces it — the floor exists to block regression, not to reward. Raising a floor
  is a one-line change in the module's pom; that is how coverage actually advances here. Do not
  describe 80% as "required".

Test structure follows Arrange-Act-Assert pattern within `src/test/java`.

## Technology Stack

**Backend**: Java 25 (compiler release 25; Docker runtime `eclipse-temurin:25-jre-alpine`), Spring Boot 4.0.x, Apache Camel, LangChain4j 1.18.0
**Frontend**: React 19, TypeScript, Vite, Mantine UI, React Flow
**Databases**: PostgreSQL with pgvector, Redis
**Build**: Maven 3.8+, Node.js 18+

## Important Notes

- LangChain4j version is managed via `langchain4j.version` property (currently 1.18.0)
- Plugins are **fat-jars** loaded from `archflow.plugins.directory` with a child-first classloader that falls back to the parent (application) classloader. The loader was NOT on the server classpath until recently — directory discovery simply did not reach the runtime, and the catalogue only had the hard-coded built-in list. It is now wired but **opt-in**: without the property no jar is opened, because opening one runs `onLoad` — arbitrary code, no sandbox, full JVM privileges. Do not make it the default. There is also NO runtime dependency resolution (the old "Jeka" claim was never implemented), so each jar must be a self-contained fat-jar. See the javadoc of `ArchflowPluginManager` / `ArchflowPluginClassLoader`.
- Frontend uses Mantine UI components (not shadcn/ui as earlier docs may state)
- Flow execution is asynchronous with built-in retry policies and parallel processing support
- **Human-in-the-loop**: a `StepType.APPROVAL` node (`HumanApprovalStep`) calls `FlowEngine.requestApproval`, which persists the state as `AWAITING_APPROVAL` and cooperatively suspends the traversal — the flow survives a process restart while it waits. `ApprovalQueueService` derives the pending queue from `StateManager.findByStatus` (no in-memory index) and routes decisions to `FlowEngine.submitApproval`. The well-known variable keys live in `ExecutionKeys` (`APPROVAL_*`), shared by the engine and the API. The reviewer's identity and comment are persisted with the decision, under the same lock — "who authorised this" must be answerable from the durable state, not from a log that can diverge
- **Tool allowlist**: `McpAgentRunner` requires a `ToolAccessPolicy` on every run and applies it twice (filtering the catalogue sent to the model, then re-checking before `callTool`). `GovernanceProfile.isToolAllowed` plugs in via `ToolAccessPolicy.of(profile)`. Adding a new agent means deciding its tool set explicitly
- **Tool result provenance**: results carry a `ToolTrust` decided by `ToolTrustPolicy` (default: everything from an MCP server is `UNTRUSTED`). Untrusted payloads go back to the model inside an `UntrustedContentFence` — a per-run nonce fence plus a system-prompt rule saying fenced content is data, never instruction. `ToolCall.resultText` stays raw for programmatic consumers; only the LLM channel sees the fence
- **Component scope**: a workflow may declare `configuration.allowedComponents` in its JSON. `DefaultWorkflowDeserializer` turns it into a `ComponentAccessPolicy` and every step resolves through a `ScopedComponentCatalog`, so a node cannot reach a component outside the list — the `ORCHESTRATE` path inherits the same scope, otherwise the restriction would be bypassable by delegation. **Absent means unrestricted** (historic behaviour). A deployment that wants the guarantee everywhere sets `archflow.components.require-allowlist=true`, which makes an absent list deny everything (fail closed) — opt-in because it requires the flows to have been migrated first
- **Untrusted input boundaries**: two, both fenced with `UntrustedContentFence` (`br.com.archflow.api.trust`) — MCP tool results (`McpAgentRunner`) and everything the browser sends to `AgUiAgentController` (`useAgentContext` values and `role:tool` messages). The user's own turn is never fenced; they are the principal. The fence rule is only injected when there is something to fence
- **Approval timeout**: `archflow.approval.timeout` (default `PT24H`, `PT0S` disables) plus `ApprovalTimeoutSweeper`. The timeout decision is always REJECTED — "nobody looked" must never become "go ahead"
- **Human gate inside the agent loop** (distinct from the graph-edge gate above): `ToolApprovalPolicy` can require a decision for a tool. The loop then suspends **before executing it** — asking after the call is notification, not approval — persists `McpAgentState` in a `McpAgentStateStore` (in-memory for dev, `JdbcMcpAgentStateStore` + migration V6_5 for prod) and returns a suspended `Result`. `McpAgentRunner.resume` continues from there; the decision is consumed once. Two details that are not optional: the fence nonce is part of the state (a new one would stop matching the rule already in the persisted system message) and so is the iteration count (otherwise repeated suspensions escape `maxIterations`). **`McpAgentState` is the durability boundary** — it is what an external orchestrator would persist instead of the table, so adopting one swaps the custodian, not the loop
- **Tool interception**: `ComponentStep` invokes components through a `ToolInterceptorChain` when one is configured; `beforeExecute` can abort. The default bean carries only additive interceptors (logging, metrics) — adding caching or guardrails there would change existing flows' behaviour
- **Homologation plan**: `docs/PLANO_HOMOLOGACAO.md` tracks the audit of announced-vs-real features. Decision 0.2: features without runtime wiring are unpublished from the docs until integrated (see the "Experimental" module list above). Decision 0.3: the stack is Spring Boot 4.0.x / Java 25 — do not "fix" docs back to Boot 3.3/Java 17.
