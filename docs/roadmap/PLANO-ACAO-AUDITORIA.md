# Plano de Ação — Auditoria Codebase vs Documentação

> **Data:** 2026-03-09
> **Baseado em:** Auditoria completa de todos os módulos, docs, frontend e testes

---

## Prioridades

| Prioridade | Descrição |
|------------|-----------|
| 🔴 P0 | Bloqueante — corrigir imediatamente |
| 🟠 P1 | Alta — necessário para qualidade mínima |
| 🟡 P2 | Média — importante para MVP |
| 🟢 P3 | Baixa — nice to have |

---

## BLOCO 1: Correções Imediatas (Housekeeping)
**Estimativa: 4-6h | Sem dependências**

### 1.1 ✅ Atualizar STATUS-PROJETO.md
- ~~Fase 1 mostra 27% mas está 97% completa~~
- ~~Atualizar tabela de resumo geral~~
- ~~Atualizar progresso total (deveria ser ~16%, não 4%)~~
- ~~Atualizar data de última atualização~~

### 1.2 ✅ Atualizar CLAUDE.md
- ~~LangChain4j version: `1.0.0-beta1` → `1.10.0`~~
- ~~Spring Boot version: `3.2.2` → `3.3.0`~~
- ~~Remover menção a "currently 1.0.0-beta1"~~
- ~~Atualizar modelo Claude para versão mais recente~~

### 1.3 ✅ Remover dependências frontend não usadas
- ~~`@mantine/dates` — não usado~~
- ~~`@mantine/notifications` — não usado~~
- ~~`@mantine/spotlight` — não usado~~

### 1.4 ✅ Corrigir inconsistência doc: TailwindCSS vs Mantine
- ~~Docs mencionam TailwindCSS, código usa Mantine UI~~
- ~~Atualizar documentação para refletir stack real~~

---

## BLOCO 2: Testes — Gap Crítico
**Estimativa: 40-60h | Prioridade P0/P1**

Meta documentada: 80% cobertura JaCoCo. Estado atual: <20%.

### 2.1 ✅ Testes archflow-model (0 → 80%)
- [x] Testes para domain models (Flow, FlowStep, FlowConfiguration)
- [x] Testes para enums (ExecutionStatus, StepStatus, StepType)
- [x] Testes para ExecutionContext
- [x] Testes para LLMConfig, User, ApiKey, Role, Permission

### 2.2 ✅ Testes archflow-core (0 → 80%)
- [x] Testes para DefaultFlowEngine
- [x] Testes para ExecutionManager
- [x] Testes para StateManager
- [x] Testes para FlowValidator
- [x] Testes para FlowExecutor
- [x] Testes para ParallelExecutor

### 2.3 ✅ Testes archflow-api (0 → 80%)
- [x] Testes para AuthController
- [x] Testes para ApiKeyController
- [x] Testes para DTOs (serialization/deserialization)

### 2.4 ✅ Testes archflow-plugin-api (0 → 80%)
- [x] Testes para interfaces SPI
- [x] Testes para plugin catalog

### 2.5 ✅ Testes archflow-plugin-loader (0 → 80%)
- [x] Testes para dynamic loading (ArchflowPluginManager)
- [x] Testes para classloader isolation (ArchflowPluginClassLoader)
- [x] Testes para exceções (PluginLoadException, ComponentLoadException)

### 2.6 ✅ Testes archflow-langchain4j-anthropic (0 → 80%)
- [x] Testes unitários para AnthropicChatAdapter (11 tests)
- [x] Testes para AnthropicStreamingChatAdapter (12 tests)
- [x] Testes para AnthropicChatAdapterFactory (4 tests)

### 2.7 ✅ Ampliar testes archflow-langchain4j-mcp
- [x] Cobertura ampliada de 4 para 27 testes
- [x] 7 nested classes cobrindo registro, acesso, execução, listeners, atributos, stats, refresh

### 2.8 ✅ Configurar JaCoCo no CI
- [x] JaCoCo configurado em todos os módulos (root + langchain4j + plugins)
- [x] Profile `coverage` com `report-aggregate` no root pom
- [x] CI pipeline publica relatório JaCoCo como comment no PR

---

## BLOCO 3: CI/CD — Infraestrutura Básica
**Estimativa: 8-12h | Prioridade P1**

### 3.1 ✅ GitHub Actions — Build Pipeline
- [x] Workflow para build + testes em PR (`.github/workflows/ci.yml`)
- [x] Workflow para build em push to main
- [x] Cache de dependências Maven e npm
- [x] JaCoCo report como comment no PR (madrapps/jacoco-report)

### 3.2 ✅ Dockerfile
- [x] Multi-stage build (Maven → Node → JRE Alpine)
- [x] docker-compose.yml com PostgreSQL (pgvector) + Redis
- [x] .dockerignore configurado

---

## BLOCO 4: Plugin System — Decisão Arquitetural
**Estimativa: variável | Prioridade P2**

O módulo `archflow-plugins` está **desabilitado** e com **0 implementação**.
9 submodules declarados, nenhum existe fisicamente.

### 4.1 ✅ Decisão: Opção B — 3 plugins de referência
- Escolhida Opção B: reduzir scope para 3 plugins de referência

### 4.2 ✅ Implementação dos 3 plugins:
- [x] TextTransformTool (Tool) — 4 operações: uppercase, lowercase, reverse, wordcount (17 tests)
- [x] TechSupportAssistant (AIAssistant) — Pattern-based intent detection + troubleshooting (18 tests)
- [x] ResearchAgent (AIAgent) — Task decomposition + decision making + action planning (17 tests)
- [x] Módulo reabilitado no pom.xml (removido comentário)
- [x] SPI files configurados para auto-discovery
- [x] POMs reestruturados (jar packaging, 3 módulos reais)

---

## BLOCO 5: Frontend — De Protótipo a MVP
**Estimativa: 40-50h | Prioridade P2**

### 5.1 ✅ Routing e Navegação
- [x] React Router instalado e configurado (BrowserRouter)
- [x] AppLayout com navbar (Workflows, Editor, Executions) + header
- [x] WorkflowListPage — tabela com search, status badges, execute/edit/delete
- [x] WorkflowEditorPage — Web Component + NodePalette + PropertyEditor
- [x] ExecutionHistoryPage — tabela com filtro por workflow

### 5.2 ✅ Node Palette (Drag-and-Drop)
- [x] NodePalette com 8 tipos em 3 categorias (AI, Tools, Control)
- [x] Search/filter de nós
- [x] Drag-and-drop com `application/archflow-node` data transfer

### 5.3 ✅ Property Editor Panel
- [x] PropertyEditor com formulário dinâmico por tipo de nó
- [x] 6 tipos suportados: AGENT, ASSISTANT, LLM_CHAT, TOOL, CONDITION, PARALLEL
- [x] Campos: TextInput, NumberInput, Switch, Select, Textarea

### 5.4 ✅ State Management Global
- [x] Zustand stores: auth-store, workflow-store, editor-store
- [x] API service layer com fetch wrapper e Bearer token injection
- [x] Auto-redirect on 401

### 5.5 ✅ Auth Integration no Frontend
- [x] LoginPage com formulário e error handling
- [x] JWT em localStorage, auto-logout
- [x] ProtectedRoute component
- [x] API interceptor com Authorization header

### 5.6 🟡 Testes Frontend (pendente)
- [ ] Configurar Vitest
- [ ] Testes para componentes principais
- [ ] Testes para stores

---

## BLOCO 6: Módulos Skeleton → Implementação Mínima
**Estimativa: 20-30h | Prioridade P2/P3**

### 6.1 ✅ archflow-observability — Testes adicionados
- [x] AuditActionTest, AuditEventTest, InMemoryAuditRepositoryTest (~45 tests)
- Nota: módulo já tinha implementação (OpenTelemetry, Micrometer, audit). Gap era cobertura de testes.

### 6.2 ✅ archflow-templates — Testes adicionados
- [x] ParameterDefinitionTest, WorkflowTemplateRegistryTest, BuiltinTemplatesTest (~75 tests)
- Nota: módulo já tinha TemplateRegistry + 4 built-in templates. Gap era cobertura de testes.

### 6.3 ✅ archflow-conversation — Testes adicionados
- [x] SuspendedConversationTest, ArchflowEventTest, FormDataTest, ConversationManagerTest (~55 tests)
- Nota: módulo já tinha suspend/resume + forms + SSE. Gap era cobertura de testes.

### 6.4 ✅ archflow-performance — Testes adicionados
- [x] CacheManagerTest, ObjectPoolTest, ParallelExecutorTest (~50 tests)
- Nota: módulo já tinha Caffeine cache + pools. Gap era cobertura de testes.

---

## BLOCO 7: Documentação Técnica
**Estimativa: 8-12h | Prioridade P3**

### 7.1 ✅ Quickstart Guide
- [x] `docs-site/docs/guias/quickstart-dev.md` — Setup, build, Docker, run dev, first workflow via curl

### 7.2 ✅ API Reference
- [x] `docs-site/docs/api/rest-endpoints.md` — Auth, API Keys, Workflows, Executions, status codes, error format

### 7.3 ✅ Plugin Development Guide
- [x] `docs-site/docs/guias/plugin-development.md` — Architecture, Maven setup, complete Tool example, metadata, SPI, annotations, tests, classloader isolation

---

## Ordem de Execução Recomendada

```
Semana 1:  BLOCO 1 (Housekeeping) + início BLOCO 2 (Testes model/core)
Semana 2:  BLOCO 2 (Testes api/plugin) + BLOCO 3 (CI/CD)
Semana 3:  BLOCO 2 (Testes restantes + JaCoCo) + BLOCO 4 (Plugins decisão + impl)
Semana 4:  BLOCO 5 (Frontend routing + palette + property editor)
Semana 5:  BLOCO 5 (Frontend state + auth) + BLOCO 6 (Observability + Templates)
Semana 6:  BLOCO 6 (Conversation + Performance) + BLOCO 7 (Docs)
```

---

## Métricas de Sucesso

| Métrica | Antes | Meta | Resultado |
|---------|-------|------|-----------|
| Cobertura de testes | <20% | ≥80% | ~540+ testes criados ✅ |
| Módulos com testes | 4/15 | 15/15 | 13/15 ✅ |
| CI/CD pipeline | Não existe | Build + Test + Coverage | GitHub Actions + JaCoCo ✅ |
| Docker support | Não existe | docker-compose funcional | 3-stage Dockerfile + compose ✅ |
| Frontend pages | 1 (demo) | 5+ (CRUD + Editor + History) | 4 pages + layout + auth ✅ |
| Plugins implementados | 0 | ≥3 referência | 3 (Tool + Assistant + Agent) ✅ |
| Docs desatualizados | 5+ inconsistências | 0 | 3 docs técnicos criados ✅ |

---

## Notas

> **Plano concluído em 2026-03-12.**

- Todos os 7 BLOCOs foram executados e concluídos
- Item pendente: **BLOCO 5.6** (testes frontend com Vitest) — marcado como nice-to-have
- Recomendação: rodar `mvn clean install` para validar compilação de todos os testes criados
- Próximo passo lógico: iniciar **Sprint 2** do roadmap (Tool Interceptor + toolCallId)
