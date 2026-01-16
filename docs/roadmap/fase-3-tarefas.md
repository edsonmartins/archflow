# FASE 3: Enterprise Capabilities - Lista de Tarefas

**Duração Estimada:** 4-6 semanas (4 sprints)
**Objetivo:** Camada enterprise para produção em ambientes corporativos
**Dependência:** FASE 1 deve estar 100% completa

---

## Sprint 9: Auth & RBAC

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F3-01 | Criar entidades User, Role, Permission | 3h | 🔴 ALTA | TODO | - |
| F3-02 | Criar entidade ApiKey com scopes | 2h | 🔴 ALTA | TODO | - |
| F3-03 | Implementar AuthService com JWT | 4h | 🔴 ALTA | TODO | - |
| F3-04 | Configurar Spring Security com JWT filter | 3h | 🔴 ALTA | TODO | - |
| F3-05 | Criar ApiKeyAuthenticationFilter | 3h | 🔴 ALTA | TODO | - |
| F3-06 | Implementar anotação @RequiresPermission | 2h | 🔴 ALTA | TODO | - |
| F3-07 | Criar PermissionAspect para validação | 2h | 🟡 MÉDIA | TODO | - |
| F3-08 | Definir roles padrão (ADMIN, DESIGNER, EXECUTOR, etc.) | 1h | 🔴 ALTA | TODO | - |
| F3-09 | Criar endpoints /api/auth (login, logout, me) | 3h | 🔴 ALTA | TODO | - |
| F3-10 | Criar endpoints /api/apikeys (create, list, revoke) | 3h | 🟡 MÉDIA | TODO | - |
| F3-11 | Configurar CORS por ambiente | 2h | 🟡 MÉDIA | TODO | - |
| F3-12 | Testar auth e permissions com integration tests | 4h | 🟡 MÉDIA | TODO | - |

**Subtotal:** 32 horas (~1 semana)

---

## Sprint 10: Observability

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F3-13 | Criar ArchflowMetrics com Micrometer | 4h | 🔴 ALTA | TODO | - |
| F3-14 | Implementar contadores (workflow, agent, tool, llm) | 3h | 🔴 ALTA | TODO | - |
| F3-15 | Implementar timers com percentis (p50, p95, p99) | 3h | 🔴 ALTA | TODO | - |
| F3-16 | Criar ArchflowTracer com OpenTelemetry | 3h | 🔴 ALTA | TODO | - |
| F3-17 | Implementar spans para workflow, agent, tool, llm | 4h | 🔴 ALTA | TODO | - |
| F3-18 | Criar AuditEvent e AuditAction enum | 2h | 🔴 ALTA | TODO | - |
| F3-19 | Implementar AuditLogger com repository | 3h | 🔴 ALTA | TODO | - |
| F3-20 | Criar tabela af_audit_log no banco | 1h | 🔴 ALTA | TODO | - |
| F3-21 | Configurar Prometheus endpoint | 2h | 🟡 MÉDIA | TODO | - |
| F3-22 | Configurar exportador OTLP para Jaeger | 2h | 🟡 MÉDIA | TODO | - |
| F3-23 | Criar dashboard básico no Grafana | 3h | 🟢 BAIXA | TODO | - |

**Subtotal:** 30 horas (~1 semana)

---

## Sprint 11: Func-Agent Mode

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F3-24 | Criar FuncAgentConfig com modos | 2h | 🔴 ALTA | TODO | - |
| F3-25 | Criar OutputSchema para validação | 3h | 🔴 ALTA | TODO | - |
| F3-26 | Implementar FuncAgentExecutor | 4h | 🔴 ALTA | TODO | - |
| F3-27 | Implementar validação de input | 2h | 🔴 ALTA | TODO | - |
| F3-28 | Implementar validação de output com schema | 3h | 🔴 ALTA | TODO | - |
| F3-29 | Criar RetryPolicy (NONE, LENIENT, STRICT, EXPONENTIAL) | 2h | 🟡 MÉDIA | TODO | - |
| F3-30 | Implementar execução com timeout | 2h | 🔴 ALTA | TODO | - |
| F3-31 | Criar DSL FuncAgent.define() | 3h | 🟡 MÉDIA | TODO | - |
| F3-32 | Criar exemplos de uso (data-extractor, csv-processor) | 2h | 🟡 MÉDIA | TODO | - |
| F3-33 | Testar modo determinístico | 3h | 🔴 ALTA | TODO | - |

**Subtotal:** 26 horas (~1 semana)

---

## Sprint 12: Multi-LLM Hub

| ID | Tarefa | Estimativa | Prioridade | Status | Última Atualização |
|----|--------|------------|------------|--------|-------------------|
| F3-34 | Criar interface LLMProvider | 2h | 🔴 ALTA | TODO | - |
| F3-35 | Criar classes LLMConfig e ModelInfo | 2h | 🔴 ALTA | TODO | - |
| F3-36 | Implementar OpenAIProvider | 4h | 🔴 ALTA | TODO | - |
| F3-37 | Implementar AnthropicProvider | 3h | 🔴 ALTA | TODO | - |
| F3-38 | Implementar AzureOpenAIProvider | 3h | 🟡 MÉDIA | TODO | - |
| F3-39 | Implementar AWSBedrockProvider | 3h | 🟡 MÉDIA | TODO | - |
| F3-40 | Implementar GoogleGeminiProvider | 2h | 🟢 BAIXA | TODO | - |
| F3-41 | Criar LLMProviderHub com registro | 3h | 🔴 ALTA | TODO | - |
| F3-42 | Implementar ModelRegistry com aliases | 2h | 🟡 MÉDIA | TODO | - |
| F3-43 | Implementar LoadBalancingStrategy | 3h | 🟡 MÉDIA | TODO | - |
| F3-44 | Criar FallbackConfig para múltiplos providers | 2h | 🟡 MÉDIA | TODO | - |
| F3-45 | Criar endpoints /api/llm (providers, models, test) | 3h | 🔴 ALTA | TODO | - |
| F3-46 | Testar switch entre providers em runtime | 3h | 🔴 ALTA | TODO | - |

**Subtotal:** 35 horas (~1 semana)

---

## 📊 Resumo da Fase 3

| Métrica | Valor |
|---------|-------|
| **Total de Tarefas** | 46 |
| **Total de Horas** | ~153 horas |
| **Sprints** | 4 |
| **Duração Estimada** | 4-6 semanas |
| **Concluídas** | 0 |
| **Em Progresso** | 0 |
| **Pendentes** | 46 |

---

## ✅ Critérios de Sucesso da Fase 3

- [ ] Autenticação JWT funcionando com refresh token
- [ ] RBAC implementado com roles e permissões granulares
- [ ] API Keys para autenticação programática
- [ ] Métricas expostas via Prometheus endpoint
- [ ] Tracing com OpenTelemetry enviando para Jaeger
- [ ] Audit logs registrados em banco de dados
- [ ] Func-agent executando com output determinístico
- [ ] Switch entre providers LLM em tempo de execução
- [ ] Load balancing entre providers configurável
- [ ] Dashboard Grafana com métricas do archflow

---

## 🔗 Dependências

| Fase | Dependência | Status |
|------|-------------|--------|
| FASE 3 | FASE 1 deve estar 100% completa | ⏳ Aguardando |
| FASE 4 | FASE 3 deve estar 100% completa | ⏳ Aguardando |

---

## 📝 Notas

- **Enterprise-first:** Recursos enterprise desde o início
- **Compliance:** Audit logs são obrigatórios para ambientes regulados
- **Performance:** Métricas devem ter < 1% overhead
- **Security:** API keys devem ter expiração configurável
