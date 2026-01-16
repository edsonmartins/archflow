# FASE 1: Foundation - Lista de Tarefas

**Duração Estimada:** 4-6 semanas (4 sprints)
**Objetivo:** Base técnica sólida com features disruptivas

---

## Sprint 1: Upgrade LangChain4j

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-01 | Analisar breaking changes LangChain4j 1.0.0 → 1.10.0 | 4h | 🔴 ALTA | TODO | - |
| F1-02 | Atualizar dependência parent pom para 1.10.0 | 1h | 🔴 ALTA | TODO | - |
| F1-03 | Migrar ChatLanguageModel para nova API | 4h | 🔴 ALTA | TODO | - |
| F1-04 | Migrar StreamingChatLanguageModel para nova API | 4h | 🔴 ALTA | TODO | - |
| F1-05 | Migrar EmbeddingModel para nova API | 2h | 🔴 ALTA | TODO | - |
| F1-06 | Atualizar adaptadores OpenAI (GPT-4.1, o1, o3-mini) | 4h | 🔴 ALTA | TODO | - |
| F1-07 | Atualizar adaptador Anthropic (Claude 3.5/3.7 Sonnet) | 4h | 🔴 ALTA | TODO | - |
| F1-08 | Corrigir compilação pós-upgrade | 4h | 🔴 ALTA | TODO | - |
| F1-09 | Atualizar testes unitários para nova API | 6h | 🟡 MÉDIA | TODO | - |
| F1-10 | Validar funcionalidades core pós-migração | 4h | 🔴 ALTA | TODO | - |

**Subtotal:** 37 horas (~1 semana)

---

## Sprint 2: Tool Interceptor + toolCallId

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-11 | Criar interface ToolInterceptor com before/after/onError | 2h | 🔴 ALTA | TODO | - |
| F1-12 | Implementar ToolInterceptorChain com ordem de execução | 3h | 🔴 ALTA | TODO | - |
| F1-13 | Criar LoggingInterceptor | 2h | 🔴 ALTA | TODO | - |
| F1-14 | Criar CachingInterceptor com TTL configurável | 4h | 🟡 MÉDIA | TODO | - |
| F1-15 | Criar MetricsInterceptor com Micrometer | 3h | 🟡 MÉDIA | TODO | - |
| F1-16 | Criar GuardrailsInterceptor para validação | 4h | 🟡 MÉDIA | TODO | - |
| F1-17 | Implementar ExecutionId com hierarquia parent-child | 3h | 🔴 ALTA | TODO | - |
| F1-18 | Implementar ExecutionTracker para rastreamento | 4h | 🔴 ALTA | TODO | - |
| F1-19 | Integrar toolCallId com ToolExecutor | 3h | 🔴 ALTA | TODO | - |

**Subtotal:** 28 horas (~1 semana)

---

## Sprint 3: Streaming Protocol

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-20 | Definir spec ArchflowEvent (domains, types, envelope) | 3h | 🔴 ALTA | TODO | - |
| F1-21 | Criar classes de modelo do Streaming Protocol | 4h | 🔴 ALTA | TODO | - |
| F1-22 | Implementar StreamingController com SSE | 4h | 🔴 ALTA | TODO | - |
| F1-23 | Implementar domain "chat" para mensagens do modelo | 3h | 🔴 ALTA | TODO | - |
| F1-24 | Implementar domain "thinking" para processamento o1 | 3h | 🟡 MÉDIA | TODO | - |
| F1-25 | Implementar domain "tool" para execução de tools | 3h | 🔴 ALTA | TODO | - |
| F1-26 | Implementar domain "audit" para tracing | 2h | 🟡 MÉDIA | TODO | - |
| F1-27 | Criar ChatPanel básico para teste de streaming | 6h | 🟡 MÉDIA | TODO | - |
| F1-28 | Testar streaming com múltiplos subscribers | 3h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 31 horas (~1 semana)

---

## Sprint 4: MCP Integration

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F1-29 | Estudar especificação MCP v1.0 | 4h | 🔴 ALTA | TODO | - |
| F1-30 | Criar interfaces MCP Server (resources, tools, prompts) | 4h | 🔴 ALTA | TODO | - |
| F1-31 | Implementar MCPServer com STDIO transport | 4h | 🔴 ALTA | TODO | - |
| F1-32 | Implementar MCPClient para chamar servidores externos | 4h | 🔴 ALTA | TODO | - |
| F1-33 | Criar ToolRegistry para descoberta de tools MCP | 3h | 🔴 ALTA | TODO | - |
| F1-34 | Expor workflows nativos como MCP tools | 3h | 🔴 ALTA | TODO | - |
| F1-35 | Implementar PromptManager do MCP | 2h | 🟡 MÉDIA | TODO | - |
| F1-36 | Testar integração com servidor MCP externo | 3h | 🔴 ALTA | TODO | - |
| F1-37 | Documentar API MCP do archflow | 2h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 29 horas (~1 semana)

---

## 📊 Resumo da Fase 1

| Métrica | Valor |
|---------|-------|
| **Total de Tarefas** | 37 |
| **Total de Horas** | ~125 horas |
| **Sprints** | 4 |
| **Duração Estimada** | 4-6 semanas |
| **Concluídas** | 0 |
| **Em Progresso** | 0 |
| **Pendentes** | 37 |

---

## ✅ Critérios de Sucesso da Fase 1

- [ ] LangChain4j 1.10.0 integrado sem erros de compilação
- [ ] Tool execution com interceptor chain funcionando
- [ ] Streaming de mensagens via SSE operacional
- [ ] MCP server rodando e respondendo a requests
- [ ] toolCallId rastreando execução hierárquica
- [ ] Pelo menos 90% dos testes passando

---

## 🔗 Dependências

| Fase | Dependência | Status |
|------|-------------|--------|
| FASE 2 | FASE 1 deve estar 100% completa | ⏳ Aguardando |
| FASE 3 | FASE 1 deve estar 100% completa | ⏳ Aguardando |

---

## 📝 Notas

- **Importante:** LangChain4j 1.10.0 tem muitos breaking changes
- **Dica:** Usar branch de feature para o upgrade
- **Validação:** Cada sprint deve ter demonstração funcional
