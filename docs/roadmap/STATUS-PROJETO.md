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
- Backend: Java 17+, Spring Boot 3.x, LangChain4j 1.10.0
- Frontend: React 19 (uso) + Web Component (distribuição)
- AI: LangChain4j 1.10.0, Spring AI 1.1+
- Protocolos: MCP v1.0, SSE, WebSocket
- Enterprise: Spring Security, Keycloak, OpenTelemetry

**Objetivo:** Primeiro lançamento (v1.0.0) em 20-30 semanas

---

## 📊 STATUS GERAL DO PROJETO

**Última atualização:** 2025-01-16

### Resumo por Fase

| Fase | Descrição | Progresso | Status | Tarefas | Horas |
|------|-----------|-----------|--------|---------|-------|
| **FASE 1** | Foundation | 27% | 🟢 IN_PROGRESS | 10/37 | ~88h/~125h |
| **FASE 2** | Visual Experience | 0% | 🔴 TODO | 0/41 | ~154h |
| **FASE 3** | Enterprise Capabilities | 0% | 🔴 TODO | 0/46 | ~153h |
| **FASE 4** | Ecosystem | 0% | 🔴 TODO | 0/49 | ~183h |
| **FASE 5** | Polish & Launch | 0% | 🔴 TODO | 0/55 | ~220h |

**Status Geral:** 🟢 **SPRINT 1 COMPLETO** - Iniciando Sprint 2: Tool Interceptor + toolCallId

**Progresso Total:** 4% (10/228 tarefas)

**Total Estimado:** ~835 horas (~20-30 semanas)

---

## 📦 Módulos Previstos

```
archflow/
├── archflow-core/                    # Core engine
├── archflow-model/                   # Domain models
├── archflow-agent/                   # Agent execution
├── archflow-plugin-api/              # Plugin SPI
├── archflow-langchain4j/             # LangChain4j 1.10.0 integration ✅
│   ├── archflow-langchain4j-core/    ✅
│   ├── archflow-langchain4j-openai/  ✅
│   ├── archflow-langchain4j-anthropic/ ✅
│   ├── archflow-langchain4j-mcp/      # PRÓXIMO
│   ├── archflow-langchain4j-streaming/ # PRÓXIMO
│   └── archflow-langchain4j-spring-ai/ # FUTURO
├── archflow-server/                  # Spring Boot 3 server
│   ├── archflow-api/
│   ├── archflow-mcp/
│   ├── archflow-streaming/
│   ├── archflow-observability/
│   └── archflow-security/
├── archflow-ui/                      # Web Component distribution
│   ├── archflow-component/
│   ├── archflow-designer/
│   ├── archflow-chat/
│   └── archflow-admin/
├── archflow-templates/               # Workflow templates
└── archflow-enterprise/              # Optional enterprise module
```

---

## 🔗 Links para Documentos de Fases

| Fase | Documento Detalhado | Status |
|------|---------------------|--------|
| [FASE 1: Foundation](./fase-1-tarefas.md) | [Ver documento](./fase-1-tarefas.md) | 🟢 Sprint 1 DONE |
| [FASE 2: Visual Experience](./fase-2-tarefas.md) | [Ver documento](./fase-2-tarefas.md) | 🔴 TODO |
| [FASE 3: Enterprise Capabilities](./fase-3-tarefas.md) | [Ver documento](./fase-3-tarefas.md) | 🔴 TODO |
| [FASE 4: Ecosystem](./fase-4-tarefas.md) | [Ver documento](./fase-4-tarefas.md) | 🔴 TODO |
| [FASE 5: Polish & Launch](./fase-5-tarefas.md) | [Ver documento](./fase-5-tarefas.md) | 🔴 TODO |

---

## 📝 Log de Mudanças

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

### Sprint 2: Tool Interceptor + toolCallId (PRÓXIMO)

| Ordem | Tarefa | ID | Estimativa |
|-------|--------|-----|------------|
| 1 | Criar interface ToolInterceptor com before/after/onError | F1-11 | 2h |
| 2 | Implementar ToolInterceptorChain com ordem de execução | F1-12 | 3h |
| 3 | Criar LoggingInterceptor | F1-13 | 2h |
| 4 | Implementar ExecutionId com hierarquia parent-child | F1-17 | 3h |
| 5 | Criar CachingInterceptor com TTL configurável | F1-14 | 4h |
| 6 | Implementar ExecutionTracker para rastreamento | F1-18 | 4h |
| 7 | Criar MetricsInterceptor com Micrometer | F1-15 | 3h |
| 8 | Criar GuardrailsInterceptor para validação | F1-16 | 4h |
| 9 | Integrar toolCallId com ToolExecutor | F1-19 | 3h |

**Subtotal Sprint 2:** 28 horas (~1 semana)

### Sprint 3: Streaming Protocol

| Ordem | Tarefa | ID | Estimativa |
|-------|--------|-----|------------|
| 1 | Definir spec ArchflowEvent (domains, types, envelope) | F1-20 | 3h |
| 2 | Criar classes de modelo do Streaming Protocol | F1-21 | 4h |
| 3 | Implementar StreamingController com SSE | F1-22 | 4h |
| 4 | Implementar domain "chat" para mensagens do modelo | F1-23 | 3h |
| 5 | Implementar domain "tool" para execução de tools | F1-25 | 3h |
| 6 | Criar ChatPanel básico para teste de streaming | F1-27 | 6h |
| 7 | Testar streaming com múltiplos subscribers | F1-28 | 3h |

### Sprint 4: MCP Integration

| Ordem | Tarefa | ID | Estimativa |
|-------|--------|-----|------------|
| 1 | Estudar especificação MCP v1.0 | F1-29 | 4h |
| 2 | Criar interfaces MCP Server (resources, tools, prompts) | F1-30 | 4h |
| 3 | Implementar MCPServer com STDIO transport | F1-31 | 4h |
| 4 | Implementar MCPClient para chamar servidores externos | F1-32 | 4h |
| 5 | Criar ToolRegistry para descoberta de tools MCP | F1-33 | 3h |

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
