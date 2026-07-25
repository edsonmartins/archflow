# Changelog

All notable changes to archflow will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-01-16

### Fase 1 - Foundation

- **LangChain4j 1.10.0 Integration** - Core adapter layer with SPI-based discovery for OpenAI, Anthropic, and 15+ LLM providers
- **Tool Interceptors** - Interceptor chain for tool calls with execution ID tracking (toolCallId system)
- **Streaming Protocol** - SSE/WebSocket streaming support for real-time agent responses
- **MCP (Model Context Protocol)** - Server and client implementation for standardized model communication

### Fase 2 - Visual

- **Web Component (`<archflow-designer>`)** - Framework-agnostic visual workflow builder, usable in React, Vue, Angular, or plain HTML
- **Node System** - Extensible node types for agents, tools, conditions, and data transformations
- **Canvas** - React Flow-based interactive canvas with drag-and-drop workflow editing
- **Execution Visualization** - Real-time execution state display within the visual designer

### Fase 3 - Enterprise

- **Auth & RBAC** - JWT authentication with role-based access control and audit logging
- **Observability** - Flow/step metrics with a pluggable exporter (Prometheus Pushgateway,
  InfluxDB, HTTP, log) and an in-process trace store behind the admin API.
  OpenTelemetry classes ship in `archflow-observability` but nothing instruments the runtime
  with them yet — no spans are emitted
- **Func-Agent** - Functional agent pattern for composable AI agent definitions
- **Multi-LLM Hub** - Unified interface to route between multiple LLM providers with fallback and load balancing

### Fase 4 - Ecosystem

- **Templates** - Pre-built workflow templates for common patterns (customer support, RAG, multi-agent)
- **Suspend/Resume** - Conversation persistence for multi-step workflows requiring human-in-the-loop
- **Marketplace** - Plugin and template marketplace infrastructure
- **Workflow-as-Tool** - Expose any workflow as a tool callable by other agents

### Core Modules

- `archflow-core` - Flow engine with workflow execution
- `archflow-model` - Domain models (Workflow, Flow, Node, Edge)
- `archflow-agent` - AI agent execution with tool support
- `archflow-plugin-api` - Plugin SPI for extensions
- `archflow-plugin-loader` - Fat-jar plugin loading with a child-first classloader (no runtime
  dependency resolution, no sandbox — only trusted jars)

### LangChain4j Modules

- `archflow-langchain4j-core` - Base adapter interfaces
- `archflow-langchain4j-openai` - OpenAI/GPT integration
- `archflow-langchain4j-anthropic` - Claude integration
- `archflow-langchain4j-openrouter` - OpenRouter integration
- `archflow-langchain4j-provider-hub` - Unified access to 15+ providers with runtime switching
- `archflow-langchain4j-mcp` - MCP client (HTTP subset + stdio) and server
- `archflow-langchain4j-memory-*` - Memory backends (Redis, JDBC)
- `archflow-langchain4j-vectorstore-*` - Vector stores (Pinecone, pgvector, Redis)

### Server & Infrastructure

- `archflow-api` - REST/WebSocket endpoints, SSE streaming, MCP client/server wiring
- `archflow-events-proto` - Event protocol shared between engine and UI
- `archflow-observability` - Audit trail (metrics/tracing classes exist but are not yet wired
  into the runtime — see below)
- `archflow-security` - JWT, RBAC and API keys
- `archflow-performance` - Caching library (not yet depended on by any other module)

### Performance Optimizations

- Caffeine-based caching with preset configurations
- Object pooling for expensive resources
- Connection pooling with health checking
- Parallel execution with virtual thread support (Java 21+)

### Documentation & Examples

- Docusaurus documentation site
- API reference (Core, Agent, LangChain4j, Streaming)
- Spring Boot, React, and Vue integration examples
- Customer support workflow demo

---

## [Unreleased]

### Added
- **`APPROVAL` step type** — the producer the human-in-the-loop gate was missing. A flow node of
  this type suspends the run in `AWAITING_APPROVAL` with a proposal, durably, and continues only
  after a decision. Downstream steps branch on `${__archflow.approvalDecision}` and read the
  human-edited payload from `approvalPayload`.
- **`ToolAccessPolicy`** — per-agent tool allowlist enforced inside the MCP tool-calling loop,
  both when building the catalogue sent to the model and again before each `callTool`.
  `McpAgentRunner` now requires one, so "no restriction" has to be written
  (`ToolAccessPolicy.allowAll()`) instead of resulting from an omission.
- **`StateManager.findByStatus`** (and `StateRepository.getStatesByStatus`) — lets the API build
  queues from durable flow state instead of an in-memory index.

### Fixed
- **Human-in-the-loop approvals were unreachable.** `requestApproval`/`submitApproval` were
  implemented and durable but had no caller: no step type invoked them and no endpoint reached
  them. Meanwhile `/api/approvals/*` served an in-memory `ApprovalRegistry` whose `register()`
  had no producer, so the queue was permanently empty and would not have survived a restart.
  The queue is now derived from durable flow state and decisions go to the engine.
- **The approval proposal was discarded.** `requestApproval(flowId, stepId, proposal)` never
  stored `proposal`, leaving the reviewer with nothing to review. It is now persisted with the
  request, alongside the step id and the request timestamp.
- **A malformed tool call invoked the tool anyway.** When the model emitted arguments that were
  not valid JSON, `McpAgentRunner` logged a warning and called the tool with an empty map —
  executing a different action from the one requested. The tool is no longer invoked; the error
  goes back to the model, which can retry.
- **The trace store had no writer.** `TraceStoreRecorder` was never registered as a bean and the
  engine received `traceRecorder = null`, so the admin observability screens could only ever be
  empty. It is now wired, and flows that fail produce a trace too (previously only the success
  path notified the recorder).
- **Flow metrics never reached the API.** The engine created a private `MetricsCollector` while
  `ObservabilityService` received `null`. Both sides now share one collector.

### Planned
- Extension marketplace launch
- Additional workflow templates
- Enhanced debugging and visual execution tracing
- Additional LLM provider adapters
