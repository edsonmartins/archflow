# Plano de Execução – archflow 2.0

> **Instrução:** Sempre que uma tarefa avançar de status, atualize esta tabela com a nova situação e registre a data no campo "Última atualização". Os status sugeridos são `TODO`, `IN_PROGRESS`, `BLOCKED` e `DONE`.

---

## Legend

| Status | Descrição |
|--------|-----------|
| `TODO` | Tarefa ainda não iniciada |
| `IN_PROGRESS` | Tarefa em execução |
| `BLOCKED` | Tarefa impedida por dependência externa |
| `DONE` | Tarefa concluída e validada |

---

## Prioridades

| Prioridade | Descrição |
|------------|-----------|
| 🔴 ALTA | Crítica para o MVP |
| 🟡 MÉDIA | Importante mas não bloqueia |
| 🟢 BAIXA | Nice to have |

---

## 📋 CONTEXTO DO PROJETO

**archflow 2.0** é a primeira plataforma visual Java-Nativa para construção de workflows de IA.

**Posicionamento Único:**
- "LangFlow para o mundo Java"
- Web Component UI (zero frontend lock-in)
- MCP (Model Context Protocol) nativo
- Enterprise features from day one

**Stack Tecnológico:**
- Backend: Java 17+, Spring Boot 3.x, LangChain4j 1.12.2
- Frontend: React 19 (uso) + Web Component (distribuição)
- AI: LangChain4j 1.12.2, Spring AI 1.1+
- Protocolos: MCP v1.0, SSE, WebSocket
- Enterprise: Spring Security, Keycloak, OpenTelemetry

**Objetivo:** Primeiro lançamento (v1.0.0) em 20-30 semanas

---

## 📊 STATUS GERAL DO PROJETO

**Última atualização:** 2026-04-08

### Resumo por Fase

| Fase | Descrição | Progresso | Status | Tarefas | Horas |
|------|-----------|-----------|--------|---------|-------|
| **FASE 1** | Foundation | 97% | ✅ DONE | 36/37 | ~125h |
| **FASE 2** | Visual Experience | 100% | ✅ DONE | 42/42 | ~156h |
| **FASE 3** | Enterprise Capabilities | 100% | ✅ DONE | 46/46 | ~153h |
| **FASE 4** | Ecosystem | 100% | ✅ DONE | 49/49 | ~183h |
| **FASE 5** | Polish & Launch | 75% | 🟡 IN_PROGRESS | 41/55 | ~220h |

**Status Geral:** Fases 1-4 completas, Fase 5 em ~75% (pendente: exemplos avançados/e2e, publicação, website e anúncios)

**Progresso Total:** ~93% (~214/229 tarefas implementadas no código)

**Total Estimado:** ~835 horas (~20-30 semanas)

### Fase 4 — Gaps completados em 2026-03-12

| Sprint | Gap anterior | Resolução |
|--------|-------------|-----------|
| Sprint 13: Templates | `TemplateMetadata` + REST | ✅ TemplateMetadata, TemplateController, DTOs, testes |
| Sprint 14: Suspend/Resume | `ConversationMessage` + REST | ✅ ConversationMessage, ConversationService, Controller, DTOs, testes |
| Sprint 15: Marketplace | Validators + REST | ✅ ExtensionSignatureValidator, PermissionValidator, DependencyResolver, Controller, DTOs, testes |
| Sprint 16: Workflow-as-Tool | `LangChain4jToolAdapter` | ✅ LangChain4jToolAdapter, SpecificationFactory, Executor, testes |

### Fase 5 — Status atual

| Sprint | Progresso | Detalhes |
|--------|-----------|----------|
| Sprint 17: Performance | ✅ 100% | RedisCacheManager, TwoLevelCache, EmbeddingCache, LlmCache, Benchmarker + testes |
| Sprint 18: Documentation | ✅ 100% | 10 docs Docusaurus (building-workflows, custom-tools, deploy, security, troubleshooting, web-component, conceitos) |
| Sprint 19: Examples | 🟡 40% | React customer-support, Spring Boot integration, READMEs. Pendentes: exemplos avançados, Vue/Angular, e2e, publicação |
| Sprint 20: Launch | 🟡 56% | CHANGELOG.md, release.yml, SECURITY.md, version 1.0.0, monitoring stack, teste completo. Pendentes: release, publicação npm/Docker, website, anúncios |

---

## 📦 Módulos — Status Real

```
archflow/
├── archflow-model/                    ✅ Domain models + testes
├── archflow-core/                     ✅ Flow Engine, Execution, Validation + testes
├── archflow-api/                      ✅ REST controllers (Auth, ApiKey) + testes
├── archflow-agent/                    ✅ ArchFlowAgent, Tool Interceptors, Deterministic mode
├── archflow-security/                 ✅ JWT, RBAC, ApiKey, CORS, Permissions
├── archflow-plugin-api/               ✅ Plugin SPI, Catalog + testes
├── archflow-plugin-loader/            ✅ Dynamic classloader, lifecycle + testes
├── archflow-plugins/                  ✅ 3 plugins referência (Tool, Assistant, Agent) + testes
├── archflow-langchain4j/              ✅ 14 submodules
│   ├── archflow-langchain4j-core/     ✅ Base adapter interfaces (SPI)
│   ├── archflow-langchain4j-openai/   ✅ + testes
│   ├── archflow-langchain4j-anthropic/ ✅ + testes
│   ├── archflow-langchain4j-mcp/      ✅ MCP registry + testes
│   ├── archflow-langchain4j-provider-hub/ ✅ Multi-LLM Hub (15+ providers)
│   ├── archflow-langchain4j-memory-*/ ✅ Redis, JDBC backends
│   ├── archflow-langchain4j-embedding-*/ ✅ OpenAI, local
│   ├── archflow-langchain4j-vectorstore-*/ ✅ Redis, pgvector, Pinecone
│   ├── archflow-langchain4j-chain-rag/ ✅ RAG chain
│   └── archflow-langchain4j-skills/   ✅ Skills integration
├── archflow-observability/            ✅ OpenTelemetry, Micrometer, Audit + testes
├── archflow-conversation/             ✅ Suspend/Resume, Forms, SSE + testes
├── archflow-performance/              ✅ Caffeine, Pools, Parallel + testes
├── archflow-templates/                ✅ Registry + 4 built-in templates + testes
├── archflow-marketplace/              ✅ ExtensionManifest, Installer, Registry, Validators + testes
├── archflow-workflow-tool/            ✅ WorkflowTool, Registry, LangChain4j Adapter + testes
├── archflow-ui/                       ✅ React 19 + Web Component + App shell + Vitest
├── .github/workflows/ci.yml          ✅ CI/CD (backend + frontend lint/test/build)
├── Dockerfile                         ✅ Multi-stage build
└── docker-compose.yml                 ✅ PostgreSQL + Redis
```

---

## 🔗 Links para Documentos de Fases

| Fase | Documento Detalhado | Status |
|------|---------------------|--------|
| [FASE 1: Foundation](./fase-1-tarefas.md) | [Ver documento](./fase-1-tarefas.md) | ✅ DONE (97%) |
| [FASE 2: Visual Experience](./fase-2-tarefas.md) | [Ver documento](./fase-2-tarefas.md) | ✅ DONE (100%) |
| [FASE 3: Enterprise Capabilities](./fase-3-tarefas.md) | [Ver documento](./fase-3-tarefas.md) | ✅ DONE (100%) |
| [FASE 4: Ecosystem](./fase-4-tarefas.md) | [Ver documento](./fase-4-tarefas.md) | ✅ DONE (100%) |
| [FASE 5: Polish & Launch](./fase-5-tarefas.md) | [Ver documento](./fase-5-tarefas.md) | 🟡 IN_PROGRESS (~75%) |

---

## 📝 Log de Mudanças

### 2026-03-12 - Sprint 20 Launch Tasks ✅
- ✅ **F5-06:** SpringCacheConfig + CachingTemplateRegistry + CachingApiKeyService + testes (6 arquivos)
- ✅ **F5-39/40:** Security audit — .gitignore atualizado, SECURITY.md criado, docker-compose.yml com warning
- ✅ **F5-41:** Versão atualizada para 1.0.0 em 35 pom.xml files
- ✅ **F5-54:** Monitoring stack — application-prod.yml, ArchflowHealthIndicator, docker-compose.monitoring.yml, Prometheus + Grafana config
- **Total sessão:** ~20 arquivos criados/editados, ~30 testes adicionados

### 2026-03-12 - Fases 4-5 Completadas (Blocos 1-8) ✅
- ✅ **Block 1:** TemplateMetadata + TemplateController + DTOs + testes (6 arquivos)
- ✅ **Block 2:** ConversationMessage + ConversationService + Controller + DTOs + testes (9 arquivos)
- ✅ **Block 3:** ExtensionSignatureValidator + PermissionValidator + DependencyResolver + MarketplaceController + testes (10 arquivos)
- ✅ **Block 4:** LangChain4jToolAdapter + WorkflowToolSpecificationFactory + LangChain4jToolExecutor + testes (5 arquivos)
- ✅ **Block 5:** ChatPanel + ChatMessage + FormRenderer + conversation-api (4 arquivos frontend)
- ✅ **Block 6:** RedisCacheManager + TwoLevelCache + EmbeddingCache + LlmCache + PerformanceBenchmarker + testes (9 arquivos)
- ✅ **Block 7:** 7 docs Docusaurus (building-workflows, custom-tools, deploy-docker, security-rbac, troubleshooting, web-component, conceitos) + sidebars
- ✅ **Block 8:** 2 exemplos (React customer-support, Spring Boot integration) + CHANGELOG.md + release.yml
- **Total sessão:** ~60 arquivos criados, ~86 testes adicionados

### 2026-03-12 - Auditoria Codebase COMPLETA ✅
- ✅ **PLANO-ACAO-AUDITORIA.md** — 7 blocos executados e concluídos
- ✅ **BLOCO 1:** Housekeeping (CLAUDE.md, STATUS-PROJETO.md, deps frontend, docs)
- ✅ **BLOCO 2:** ~540+ testes criados em 13 módulos (model, core, api, plugin-api, plugin-loader, langchain4j-anthropic, langchain4j-mcp, plugins×3, observability, conversation, performance, templates)
- ✅ **BLOCO 3:** CI/CD — GitHub Actions (build+frontend jobs), Dockerfile multi-stage, docker-compose.yml
- ✅ **BLOCO 4:** 3 plugins de referência — TextTransformTool, TechSupportAssistant, ResearchAgent (52 tests)
- ✅ **BLOCO 5:** Frontend MVP — routing, 4 pages, 3 Zustand stores, auth, NodePalette, PropertyEditor
- ✅ **BLOCO 6:** Testes para módulos skeleton (~225 tests em observability, conversation, performance, templates)
- ✅ **BLOCO 7:** Documentação técnica — quickstart-dev.md, rest-endpoints.md, plugin-development.md

### 2026-04-08 - Auditoria de Build/Testes Corrigida ✅
- ✅ `mvn clean verify -Pcoverage` passando no reactor completo (35 módulos)
- ✅ `mvn clean package -DskipTests` validado para fluxo de release backend
- ✅ `archflow-ui`: `npm run lint`, `npm run test:run` (31/31) e `npm run build` passando
- ✅ `docs-site`: `npm ci` e `npm run build` passando para `pt-BR` e `en`
- ✅ CI frontend atualizado para executar Vitest
- ⚠️ Lint frontend passa com warnings de variáveis não usadas; `no-explicit-any` foi desativado para APIs dinâmicas do Web Component

### 2025-01-16 - Sprint 1 COMPLETO ✅
- ✅ **Sprint 1: Upgrade LangChain4j 1.0.0-beta1 → 1.10.0** - TODAS AS 10 TAREFAS COMPLETAS
- ✅ LangChain4j 1.10.0 integrado (19 módulos compilando)
- ✅ Adicionado langchain4j-bom para gerenciamento de dependências
- ✅ Spring Boot atualizado de 3.2.2 → 3.3.0
- ✅ Corrigido FlowState (anotações Lombok) e StepType (adicionado CHAIN)
- ✅ **Breaking Changes Migrados:**
  - `ChatLanguageModel` → `ChatModel`
  - `StreamingChatLanguageModel` → `StreamingChatModel`
  - `ConversationalChain.chatLanguageModel()` → `.chatModel()`
  - `model.chat()` retorna String diretamente
- ✅ **Adapters Criados:**
  - OpenAiChatAdapter + OpenAiChatAdapterFactory (SPI)
  - OpenAiStreamingChatAdapter (StreamingChatModel)
  - AnthropicChatAdapter + AnthropicChatAdapterFactory (SPI)
  - AnthropicStreamingChatAdapter (StreamingChatModel)
- ✅ **Vector Stores:**
  - RedisVectorStoreAdapter (Jedis direto - community module não disponível em 1.10.0)
  - PgVectorStoreAdapter (PostgreSQL + pgvector)
  - PineconeVectorStoreAdapter (HTTP API)
- ✅ **Testes:** 18 unitários passando (9 OpenAI + 9 OpenAI Streaming)
- ✅ **Commit:** 7144f91 - feat: LangChain4j upgrade to 1.10.0 - Sprint 1 Foundation Complete

### 2025-01-15
- ✅ Criação do documento de status principal (STATUS-PROJETO.md)
- ✅ Criação dos documentos de tarefas por fase (fase-*-tarefas.md)
- 📋 Projeto definido com 228 tarefas distribuídas em 5 fases
- 📊 Total estimado: ~835 horas (20-30 semanas)

---

## 🎯 Sequência de Próximos Passos

> Sprints 2-12 já concluídos (Fases 1-3). Próximo trabalho: completar Fase 4 e iniciar Fase 5.

### Próximos passos para v1.0.0

Restam tarefas de exemplos, release/publicação e marketing:

| Tarefa | Sprint | Estimativa |
|--------|--------|------------|
| Rodar test suite completo (`mvn clean verify -Pcoverage`) | Sprint 20 | 3h |
| Audit de segurança (dependency scan) | Sprint 20 | 2h |
| Atualizar versão para 1.0.0 no pom.xml | Sprint 20 | 1h |
| Criar tag git v1.0.0 | Sprint 20 | 0.5h |
| Build e publicar Docker images | Sprint 20 | 2h |
| Publicar npm @archflow/component 1.0.0 | Sprint 20 | 1h |
| Criar GitHub Release com assets | Sprint 20 | 1h |
| Exemplos Vue e Angular (opcional) | Sprint 19 | 8h |

---

## 🔬 Decisão Arquitetural: React 19 + Web Component

### Análise Completa Realizada

**Data:** 15 de Janeiro de 2026
**Documento:** [docs/analysis/react-to-web-component-analysis.md](../analysis/react-to-web-component-analysis.md)

### Conclusão

✅ **React 19 (Dez/2024) tem suporte NATIVO a Web Components**

| Opção | Viabilidade | Risco | Decisão |
|-------|-------------|-------|---------|
| **React 19 Nativo** | ✅ Alta | 🟢 Baixo | ✅ **ESCOLHIDO** |
| @r2wc/react-to-web-component | ⚠️ Média | 🟠 Médio | ❌ Descartado (baixa manutenção) |
| Preact | ✅ Alta | 🟢 Baixo | ⚠️ Alternativa se necessário |
| Svelte → WC | ✅ Alta | 🟢 Baixo | ❌ Stack diferente |

### Estratégia de Implementação

```
archflow-ui/
├── archflow-component/          # Web Component (TypeScript puro)
│   ├── src/
│   │   ├── ArchflowDesigner.ts  # HTMLElement class
│   │   ├── Canvas.ts
│   │   ├── nodes/
│   │   └── styles/
│   └── package.json             # @archflow/component
│
└── examples/
    └── react/                   # Exemplo React 19
        └── App.tsx              # <archflow-designer> direto
```

### Problemas Conhecidos e Mitigações

| Problema | Mitigação |
|----------|-----------|
| Attributes vs Properties | Implementar ambos no WC |
| Sem Declarative Shadow DOM | Client-side rendering |

### Fontes

- [React v19 Announcement](https://react.dev/blog/2024/12/05/react-19)
- [React 19 and Web Component Examples](https://frontendmasters.com/blog/react-19-and-web-component-examples/)

---

## 📌 Notas Importantes

- **Framework target:** LangChain4j 1.10.0 ✅ ATINGIDO
- **Breaking changes:** Muitos entre 1.0.0 e 1.10.0 ✅ RESOLVIDOS
- **Diferencial principal:** Web Component UI
- **MCP é prioridade:** 3 de 6 concorrentes já têm
- **Enterprise from day one:** RBAC, audit, métricas, compliance
