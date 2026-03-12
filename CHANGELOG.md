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
- **Observability** - Prometheus metrics export and OpenTelemetry distributed tracing
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
- `archflow-plugin-loader` - Dynamic plugin loading with Jeka and classloader isolation

### LangChain4j Modules

- `archflow-langchain4j-core` - Base adapter interfaces
- `archflow-langchain4j-openai` - OpenAI/GPT integration
- `archflow-langchain4j-anthropic` - Claude integration
- `archflow-langchain4j-streaming` - Streaming support
- `archflow-langchain4j-spring-ai` - Spring AI adapter
- `archflow-langchain4j-memory-*` - Memory backends (Redis, JDBC)
- `archflow-langchain4j-vectorstore-*` - Vector stores (Pinecone, pgvector, Redis)

### Server & Infrastructure

- `archflow-api` - REST/WebSocket endpoints
- `archflow-streaming` - SSE/WebSocket streaming protocol
- `archflow-mcp` - MCP server/client
- `archflow-observability` - Metrics and tracing
- `archflow-security` - RBAC and SSO support
- `archflow-performance` - Caching, pooling, parallel execution

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

### Planned
- Extension marketplace launch
- Additional workflow templates
- Enhanced debugging and visual execution tracing
- Additional LLM provider adapters
