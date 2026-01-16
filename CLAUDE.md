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
- **archflow-plugin-loader** - Dynamic plugin loading with Jeka, classloader isolation
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

### Frontend
- **archflow-ui** - React + TypeScript + Vite, uses Mantine UI and React Flow for visual workflow designer

## Key Architectural Patterns

1. **Adapter Pattern** - LangChain4j integrations use Apache Camel-style adapters for extensibility
2. **Plugin Architecture** - Dynamic loading with dependency management via Jeka
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

**Backend**: Java 17+, Spring Boot 3.2.2, Apache Camel 4.3.0, LangChain4j 1.0.0-beta1
**Frontend**: React 19, TypeScript, Vite, Mantine UI, React Flow
**Databases**: PostgreSQL with pgvector, Redis
**Build**: Maven 3.8+, Node.js 18+

## Important Notes

- LangChain4j version is managed via `langchain4j.version` property (currently 1.0.0-beta1)
- Plugin dependencies are resolved dynamically at runtime using Jeka
- Frontend uses Mantine UI components (not shadcn/ui as earlier docs may state)
- Flow execution is asynchronous with built-in retry policies and parallel processing support
