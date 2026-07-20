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
- **archflow-conversation** - Suspend/resume wired to the server; guardrails/governance/episodic memory/summarizer are an OPT-IN library not yet called by the archflow-api execution path

### Experimental / not wired to the runtime (honest state — see docs/PLANO_HOMOLOGACAO.md)
- **archflow-brainsentry** - Brain Sentry client library; not on the archflow-api runtime classpath
- **archflow-observability** - OTel/Micrometer classes exist but nothing instruments the runtime (real observability today: API trace store + Actuator health)
- **archflow-performance** - Two-level cache library; orphan module, no pom depends on it
- **archflow-marketplace** - Extension manifest catalog; "install" registers a manifest only, RSA signature verification has no trusted keys (checksum in practice)

### Frontend
- **archflow-ui** - React + TypeScript + Vite, uses Mantine UI and React Flow for visual workflow designer

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
- **JaCoCo** for coverage (minimum 80% required)

Test structure follows Arrange-Act-Assert pattern within `src/test/java`.

## Technology Stack

**Backend**: Java 25 (compiler release 25; Docker runtime `eclipse-temurin:25-jre-alpine`), Spring Boot 4.0.x, Apache Camel, LangChain4j 1.12.2
**Frontend**: React 19, TypeScript, Vite, Mantine UI, React Flow
**Databases**: PostgreSQL with pgvector, Redis
**Build**: Maven 3.8+, Node.js 18+

## Important Notes

- LangChain4j version is managed via `langchain4j.version` property (currently 1.12.2)
- Plugins are **fat-jars** loaded from a plugins directory with a child-first classloader that falls back to the parent (application) classloader. There is NO runtime dependency resolution (the old "Jeka" claim was never implemented) and NO sandbox — `onLoad` runs arbitrary jar code, so only trusted jars may be loaded. See the javadoc of `ArchflowPluginManager` / `ArchflowPluginClassLoader`.
- Frontend uses Mantine UI components (not shadcn/ui as earlier docs may state)
- Flow execution is asynchronous with built-in retry policies and parallel processing support
- **Homologation plan**: `docs/PLANO_HOMOLOGACAO.md` tracks the audit of announced-vs-real features. Decision 0.2: features without runtime wiring are unpublished from the docs until integrated (see the "Experimental" module list above). Decision 0.3: the stack is Spring Boot 4.0.x / Java 25 — do not "fix" docs back to Boot 3.3/Java 17.
