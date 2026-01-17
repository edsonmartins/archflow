# Changelog

All notable changes to archflow will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-01-16

### Added

#### Core Modules
- `archflow-core` - Flow engine with workflow execution
- `archflow-model` - Domain models (Workflow, Flow, Node, Edge)
- `archflow-agent` - AI agent execution with tool support
- `archflow-plugin-api` - Plugin SPI for extensions
- `archflow-plugin-loader` - Plugin loading system

#### LangChain4j Integration
- `archflow-langchain4j-core` - Base interfaces
- `archflow-langchain4j-openai` - OpenAI/GPT integration
- `archflow-langchain4j-anthropic` - Claude integration
- `archflow-langchain4j-streaming` - Streaming support
- `archflow-langchain4j-spring-ai` - Spring AI adapter

#### Server & API
- `archflow-api` - REST/WebSocket endpoints
- `archflow-streaming` - SSE/WebSocket streaming protocol
- `archflow-mcp` - MCP (Model Context Protocol) server/client
- `archflow-observability` - Metrics (Prometheus), Tracing (OpenTelemetry)
- `archflow-security` - RBAC, SSO support

#### Performance
- `archflow-performance` - Caching, pooling, parallel execution

#### Templates & Tools
- `archflow-templates` - Workflow templates
- `archflow-workflow-tool` - Workflow-as-Tool pattern
- `archflow-conversation` - Suspend/resume conversations

#### Features
- Visual workflow designer (Web Component)
- Agent execution with tools
- Tool interceptor chain
- Execution ID tracking (toolCallId system)
- Suspend/Resume conversations
- Multi-agent coordination
- RAG with vector stores
- 15+ LLM provider support

#### Documentation
- Docusaurus documentation site
- API reference (Core, Agent, LangChain4j, Streaming)
- Guides (First workflow, AI agent, RAG, Multi-agent)
- Integration guides (Spring Boot, MCP, Observability)

#### Examples
- Spring Boot example app
- React integration example
- Vue integration example

### Performance Optimizations
- Caffeine-based caching with preset configurations
- Object pooling for expensive resources
- Connection pooling with health checking
- Parallel execution with virtual thread support (Java 21+)

### Enterprise Features
- RBAC (Role-Based Access Control)
- Audit logging
- Metrics export (Prometheus)
- Distributed tracing (OpenTelemetry)
- Suspend/Resume for multi-step conversations

---

## [Unreleased]

### Planned
- Extension marketplace
- More workflow templates
- Additional LLM providers
- Enhanced debugging tools
- Visual execution tracing
