---
title: Quickstart (Desenvolvimento)
sidebar_position: 0
slug: quickstart-dev
---

# Quickstart — Desenvolvimento Local

Guia para configurar o ambiente, compilar e rodar o archflow localmente para desenvolvimento.

## Requisitos

| Ferramenta | Versão mínima | Recomendado |
|-----------|---------------|-------------|
| Java JDK | 17 | 21 (virtual threads) |
| Maven | 3.8+ | 3.9+ |
| Node.js | 18+ | 20 LTS |
| Docker | 24+ | Última estável |
| Git | 2.30+ | Última estável |

## 1. Clonar o repositório

```bash
git clone https://github.com/archflow/archflow.git
cd archflow
```

## 2. Build do backend

```bash
# Build completo com testes
mvn clean install

# Build rápido (sem testes)
mvn clean install -DskipTests

# Build com relatório de cobertura
mvn clean verify -Pcoverage
```

O build compila 15 módulos Maven incluindo core, model, API, plugins e integrações LangChain4j.

## 3. Build do frontend

```bash
cd archflow-ui
npm install
npm run dev        # Dev server em http://localhost:5173
```

Para build de produção:

```bash
npm run build      # Gera o Web Component em dist/
npm run lint       # Verifica code style
npm run test       # Roda testes Vitest
```

## 4. Infraestrutura local (Docker)

O projeto inclui `docker-compose.yml` com PostgreSQL (pgvector) e Redis:

```bash
# Subir toda a infraestrutura + app
docker compose up -d

# Apenas banco e cache (para desenvolvimento)
docker compose up postgres redis -d
```

Serviços disponíveis:

| Serviço | Porta | Descrição |
|---------|-------|-----------|
| App | 8080 | Backend Spring Boot + Frontend |
| PostgreSQL | 5432 | Banco com pgvector (user: archflow/archflow) |
| Redis | 6379 | Cache e sessões |

## 5. Rodar o backend em modo dev

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

O backend estará disponível em `http://localhost:8080`.

## 6. Estrutura do projeto

```
archflow/
├── archflow-model/            # Domain models (Flow, FlowStep, AIComponent)
├── archflow-core/             # Flow Engine, Execution Manager, Validation
├── archflow-api/              # REST API contracts (Auth, ApiKey)
├── archflow-agent/            # ArchFlowAgent entry point
├── archflow-security/         # RBAC, JWT, API Key management
├── archflow-plugin-api/       # Plugin SPI, Catalog, Versioning
├── archflow-plugin-loader/    # Dynamic classloader, plugin lifecycle
├── archflow-plugins/          # Reference plugins (Tool, Assistant, Agent)
├── archflow-langchain4j/      # LangChain4j adapters (13 submodules)
├── archflow-observability/    # Metrics, Tracing, Audit
├── archflow-templates/        # Workflow templates (4 built-in)
├── archflow-conversation/     # Suspend/Resume, Forms, SSE events
├── archflow-performance/      # Caching, Pooling, Parallel execution
├── archflow-ui/               # React + Web Component frontend
├── .github/workflows/         # CI/CD (GitHub Actions)
├── Dockerfile                 # Multi-stage build
└── docker-compose.yml         # PostgreSQL + Redis
```

## 7. Executar testes

```bash
# Todos os testes
mvn test

# Módulo específico
mvn test -pl archflow-core

# Classe específica
mvn test -pl archflow-model -Dtest=FlowStateTest

# Método específico
mvn test -pl archflow-model -Dtest=FlowStateTest#shouldBuild

# Relatório de cobertura (individual por módulo)
mvn jacoco:report
# Relatório em: target/site/jacoco/index.html

# Relatório agregado (todos os módulos)
mvn clean verify -Pcoverage
```

## 8. Criar seu primeiro workflow (API)

Com o backend rodando:

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin"}'

# Criar workflow
curl -X POST http://localhost:8080/api/workflows \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "metadata": {
      "name": "My First Workflow",
      "description": "Hello World workflow",
      "version": "1.0.0"
    },
    "steps": [],
    "configuration": {}
  }'

# Executar workflow
curl -X POST http://localhost:8080/api/workflows/<id>/execute \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"input": "Hello"}'
```

## Próximos passos

- [Primeiro Workflow (detalhado)](./primeiro-workflow) — Criar um workflow AI completo
- [API Reference](../api/rest-endpoints) — Todos os endpoints REST
- [Plugin Development](./plugin-development) — Criar plugins customizados
- [Arquitetura](../conceitos/) — Entender a arquitetura do archflow
