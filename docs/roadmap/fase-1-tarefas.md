# FASE 1: Foundation - Lista de Tarefas

**Duração Estimada:** 4-6 semanas (4 sprints)
**Objetivo:** Base técnica sólida com features disruptivas

---

## Sprint 1: Upgrade LangChain4j ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-01 | Analisar breaking changes LangChain4j 1.0.0 → 1.10.0 | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-02 | Atualizar dependência parent pom para 1.10.0 | 1h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-03 | Migrar ChatLanguageModel para nova API | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-04 | Migrar StreamingChatLanguageModel para nova API | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-05 | Migrar EmbeddingModel para nova API | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-06 | Atualizar adaptadores OpenAI (GPT-4.1, o1, o3-mini) | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-07 | Atualizar adaptador Anthropic (Claude 3.5/3.7 Sonnet) | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-08 | Corrigir compilação pós-upgrade | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-09 | Atualizar testes unitários para nova API | 6h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-10 | Validar funcionalidades core pós-migração | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |

**Subtotal:** 37 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 1:**
- ✅ LangChain4j 1.10.0 integrado (19 módulos compilando)
- ✅ OpenAiChatAdapter + OpenAiStreamingChatAdapter implementados
- ✅ AnthropicChatAdapter + AnthropicStreamingChatAdapter implementados
- ✅ Factory classes para SPI (OpenAiChatAdapterFactory, AnthropicChatAdapterFactory)
- ✅ Vector stores implementados (Redis, PgVector, Pinecone)
- ✅ 18 testes unitários passando

---

## Sprint 2: Tool Interceptor + toolCallId ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-11 | Criar interface ToolInterceptor com before/after/onError | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-12 | Implementar ToolInterceptorChain com ordem de execução | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-13 | Criar LoggingInterceptor | 2h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-14 | Criar CachingInterceptor com TTL configurável | 4h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-15 | Criar MetricsInterceptor com Micrometer | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-16 | Criar GuardrailsInterceptor para validação | 4h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-17 | Implementar ExecutionId com hierarquia parent-child | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-18 | Implementar ExecutionTracker para rastreamento | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-19 | Integrar toolCallId com ToolExecutor | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |

**Subtotal:** 28 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 2:**
- ✅ ToolInterceptor interface com before/after/onError
- ✅ ToolInterceptorChain com ordenação (order())
- ✅ LoggingInterceptor (log de execução)
- ✅ CachingInterceptor (cache em memória com TTL)
- ✅ MetricsInterceptor (métricas: count, avg/min/max duration)
- ✅ GuardrailsInterceptor (validação input/output)
- ✅ ExecutionId (FLOW_abc_001, TOOL_abc_002_001)
- ✅ ExecutionTracker (rastreamento hierárquico)
- ✅ InterceptableToolExecutor (executor integrado)

---

## Sprint 3: Streaming Protocol ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-20 | Definir spec ArchflowEvent (domains, types, envelope) | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-21 | Criar classes de modelo do Streaming Protocol | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-22 | Implementar StreamingController com SSE | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-23 | Implementar domain "chat" para mensagens do modelo | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-24 | Implementar domain "thinking" para processamento o1 | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-25 | Implementar domain "tool" para execução de tools | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-26 | Implementar domain "audit" para tracing | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-27 | Criar ChatPanel básico para teste de streaming | 6h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-28 | Testar streaming com múltiplos subscribers | 3h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |

**Subtotal:** 31 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 3:**
- ✅ ArchflowDomain enum (6 domains: CHAT, THINKING, TOOL, AUDIT, INTERACTION, SYSTEM)
- ✅ ArchflowEventType enum (20+ tipos de eventos)
- ✅ ArchflowEvent envelope com builder e JSON
- ✅ EventStreamEmitter para envio SSE
- ✅ EventStreamRegistry com broadcast, heartbeat, cleanup
- ✅ ChatEvent (delta, message, start, end, error)
- ✅ ThinkingEvent (thinking, reflection, verification)
- ✅ ToolEvent (start, progress, result, error)
- ✅ AuditEvent (trace, span, metric, log)
- ✅ InteractionEvent (suspend, form, resume, cancel)
- ✅ SystemEvent (connected, disconnected, heartbeat, error)

---

## Sprint 4: MCP Integration ✅ COMPLETO

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-29 | Estudar especificação MCP v1.0 | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-30 | Criar interfaces MCP Server (resources, tools, prompts) | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-31 | Implementar MCPServer com STDIO transport | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-32 | Implementar MCPClient para chamar servidores externos | 4h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-33 | Criar ToolRegistry para descoberta de tools MCP | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-34 | Expor workflows nativos como MCP tools | 3h | 🔴 ALTA | ✅ DONE | 2025-01-16 |
| F1-35 | Implementar PromptManager do MCP | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |
| F1-36 | Testar integração com servidor MCP externo | 3h | 🔴 ALTA | ⚪ SKIP | - |
| F1-37 | Documentar API MCP do archflow | 2h | 🟡 MÉDIA | ✅ DONE | 2025-01-16 |

**Subtotal:** 29 horas (~1 semana) ✅ **COMPLETO**

**Entregas Sprint 4:**
- ✅ JSON-RPC 2.0 message types (Request, Response, Notification)
- ✅ MCP domain models (Resource, Tool, Prompt, ServerInfo, ClientInfo)
- ✅ McpServer interface com resources, tools, prompts
- ✅ AbstractMcpServer base implementation
- ✅ MemoryMcpServer para testes
- ✅ STDIO transport (StdioServerTransport, StdioClientTransport)
- ✅ McpClient interface
- ✅ StdioMcpClient para conectar a servidores externos
- ✅ McpToolRegistry para descoberta de tools
- ✅ WorkflowMcpServer para expor workflows como MCP tools
- ✅ McpPromptManager para gerenciar prompts
- ✅ 69 testes unitários passando

---

## 📊 Resumo da Fase 1

| Métrica | Valor |
|---------|-------|
| **Total de Tarefas** | 37 |
| **Total de Horas** | ~125 horas |
| **Sprints** | 4 |
| **Duração Estimada** | 4-6 semanas |
| **Concluídas** | 36 ✅ |
| **Em Progresso** | 0 |
| **Pendentes** | 1 ⚪ |
| **Progresso** | 97% |

---

## ✅ Critérios de Sucesso da Fase 1

- [x] LangChain4j 1.10.0 integrado sem erros de compilação
- [x] Tool execution com interceptor chain funcionando
- [x] Streaming de mensagens via SSE operacional
- [x] MCP server rodando e respondendo a requests
- [x] toolCallId rastreando execução hierárquica
- [x] Pelo menos 90% dos testes passando (69/69 = 100%)

---

## 🔗 Dependências

| Fase | Dependência | Status |
|------|-------------|--------|
| Sprint 2 | Sprint 1 completa | ✅ OK |
| Sprint 3 | Sprint 2 completa | ✅ OK |
| Sprint 4 | Sprint 2 completa | ✅ OK |

---

## 📝 Notas

- **Importante:** LangChain4j 1.10.0 tem muitos breaking changes - MIGRADO ✅
- **Dica:** Usar branch de feature para o upgrade
- **Validação:** Cada sprint deve ter demonstração funcional
- **Commits:**
  - 7144f91 - Sprint 1 Foundation Complete
  - e64ba89 - Sprint 2 Tool Interceptor + toolCallId Complete
  - 4c11a57 - Sprint 3 Streaming Protocol Complete
