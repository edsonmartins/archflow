# RFC-005 v2 — O que Implementar no ArchFlow para o VendaX

| Campo | Valor |
|---|---|
| **ID** | RFC-005 v2 |
| **Status** | EM DISCUSSÃO |
| **Data** | Abril 2026 |
| **Autor** | IntegrAllTech — Edson Martins |
| **Substitui** | RFC-005 v1 (mesclava lógica de negócio com motor genérico) |
| **Decisões relacionadas** | ADR-003, ADR-007, ADR-008, RFC-006 |
| **Complementar** | RFC-006 — estrutura do repositório VendaX (lógica de negócio) |

---

## Princípio Central — A Separação Correta

A versão anterior deste RFC cometia um erro arquitetural: misturava lógica de motor genérico com lógica de negócio do VendaX dentro do ArchFlow. Essa revisão corrige isso.

> **O critério de separação:**
> O ArchFlow é um motor de orquestração de agentes de propósito geral.
> Ele não sabe nada sobre food distribution, distribuidoras, churn ou vendedores.
>
> **Teste:** se remover o VendaX e construir um sistema jurídico sobre o ArchFlow, esse conceito ainda faz sentido? Se sim → pertence ao ArchFlow. Se não → pertence ao VendaX.

Aplicando o teste: `PlaybookConfig`, `TaskEngine`, os 14 agentes, as MCP tools, `Nexus` e `Argos` — nenhum desses conceitos pertence ao motor genérico. Todos vivem no repositório do VendaX e consomem o ArchFlow como dependência (ver RFC-006).

O que pertence ao ArchFlow é exclusivamente o que torna o motor mais capaz, robusto e seguro para **qualquer** produto construído sobre ele — VendaX, PullWise.ai, Gestor-RQ ou qualquer produto futuro da IntegrAllTech.

### Legenda de status

| Símbolo | Significado |
|---|---|
| ✅ | Existe e funciona — pode ser usado diretamente |
| ⚠️ | Existe parcialmente — precisa ser estendido ou corrigido |
| ❌ | Não existe — precisa ser criado do zero |

---

## 1. Multi-tenancy no Core — 🔴 BLOQUEANTE

O único gap verdadeiramente crítico do ArchFlow para o VendaX. Não é lógica de negócio — é responsabilidade do **motor** garantir isolamento entre tenants em todas as camadas. Sem isso, nenhum produto multi-tenant pode rodar com segurança sobre o ArchFlow.

### 1.1 ExecutionContext

**Estado atual:** `HashMap` mutável sem `tenantId`. Ponto central de propagação — passado para todo agente, toda tool e todo interceptor.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | Converter para `record` imutável com campos de primeira classe: `tenantId` (obrigatório), `userId`, `sessionId`, `requestId` (UUID auto-gerado) | Novo | 2 dias |
| ❌ | Substituir `HashMap<String,Object>` mutável por `unmodifiableMap`. Adicionar `withVariable(key, value)` que retorna nova instância | Mudança | 1 dia |
| ❌ | Método `snapshot()` que congela o contexto no início da execução — nenhum agente modifica o contexto de outro em paralelo | Novo | 0.5 dia |
| ⚠️ | Manter `tenantId="SYSTEM"` como default temporário — permite compilar sem quebrar os 107 testes existentes | Extensão | 0.5 dia |
| ❌ | Campo `variables: Map<String,Object>` de propósito geral — é aqui que o VendaX injetará o `PlaybookConfig` resolvido, sem que o ArchFlow precise conhecer seu tipo | Novo | 0.5 dia |

```java
// Novo contrato do ExecutionContext
public record ExecutionContext(
    String tenantId,              // obrigatório — não pode ser null em produção
    String userId,                // obrigatório
    String sessionId,             // chave de isolamento de conversa
    String requestId,             // UUID gerado automaticamente
    ChatMemory chatMemory,        // isolado por sessionId + tenantId
    FlowState flowState,          // imutável por rebuild
    Map<String, Object> variables, // unmodifiableMap — VendaX injeta PlaybookConfig aqui
    ExecutionMetrics metrics       // métricas da execução atual
) { ... }
```

> ⚠️ **Nota importante:** `PlaybookConfig` **não entra** no `ExecutionContext` como campo tipado.
> O ArchFlow não conhece `PlaybookConfig`. A solução correta: o VendaX injeta o `PlaybookConfig`
> resolvido em `variables` antes de acionar qualquer agente. O motor passa o contexto inteiro
> para os agentes — eles extraem o que precisam. O ArchFlow permanece agnóstico ao conteúdo de `variables`.

---

### 1.2 ChatMemory — Redis

**Estado atual:** chave `archflow:chat:<conversationId>` sem `tenantId` — risco de colisão entre tenants.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | Alterar chave Redis de `archflow:chat:<conversationId>` para `archflow:chat:<tenantId>:<sessionId>` | Mudança | 0.5 dia |
| ❌ | Script de migração de chaves existentes antes do deploy multi-tenant | Novo | 0.5 dia |
| ⚠️ | Backend JDBC para `ChatMemory`: declarado mas incompleto — implementar para ambientes sem Redis | Completar | 1 dia |

---

### 1.3 EpisodicMemory

**Estado atual:** interface usa `contextId` sem `tenantId` — busca por similaridade pode retornar episódios de outro tenant.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | Adicionar `tenantId` ao modelo `Episode` como campo obrigatório | Mudança | 0.5 dia |
| ⚠️ | Atualizar todos os métodos da interface com `tenantId` explícito: `store(tenantId, episode)`, `recall(tenantId, query)`, `getByContext(tenantId, contextId)` | Mudança | 1 dia |
| ⚠️ | `InMemoryEpisodicMemory`: particionar mapa interno por `tenantId` — `Map<String, List<Episode>>` | Extensão | 0.5 dia |
| ✅ | `BrainSentryMemoryAdapter`: já propaga `tenantId` — validar que não há bypass possível | Validação | 0.5 dia |

---

### 1.4 FlowState e StateRepository

**Estado atual:** `FlowState` sem `tenantId`, repositórios in-memory sem particionamento.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | Adicionar `tenantId` ao `FlowState` como campo imutável | Mudança | 0.5 dia |
| ❌ | `StateRepository`: `tenantId` obrigatório em todos os métodos CRUD. Nunca expor busca sem filtro de tenant. | Mudança | 1 dia |
| ❌ | `StateRepositoryJdbc`: implementação JPA para produção — schema com `tenant_id NOT NULL` e índice composto `(tenant_id, flow_id)` | Novo | 2 dias |

```sql
-- Schema SQL — StateRepositoryJdbc
CREATE TABLE flow_states (
    tenant_id     VARCHAR(36) NOT NULL,
    flow_id       VARCHAR(36) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    current_step_id VARCHAR(64),
    variables     JSON,
    metrics       JSON,
    error         JSON,
    updated_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, flow_id),
    INDEX idx_tenant_status (tenant_id, status)
);
```

---

### 1.5 ConversationManager

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ⚠️ | Adicionar `tenantId` ao registro de subscribers — uma conversa só notifica subscribers do mesmo tenant | Extensão | 1 dia |
| ⚠️ | `ArchflowEvent`: adicionar `tenantId` — evento precisa carregar o tenant desde a origem | Extensão | 0.5 dia |

---

## 2. TenantScheduler — 🔴 BLOQUEANTE

O ArchFlow não tem scheduler genérico para agentes. O VendaX precisa de jobs cron por tenant (briefing semanal, avaliações mensais, plano trimestral). Essa é capacidade do **motor** — qualquer produto construído sobre o ArchFlow pode precisar de rotinas agendadas por tenant.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | `TenantScheduler`: aceita `ScheduledJob { tenantId, jobId, cronExpression, agentId, payload }` e registra no Quartz | Novo | 2 dias |
| ❌ | Integração com Quartz Scheduler: cada job identificado por `(tenantId, jobId)` — isolamento por chave composta | Novo | 2 dias |
| ❌ | API para registrar/remover/atualizar jobs por tenant: o VendaX usa ao salvar `WeekSchedule`. O ArchFlow não conhece `WeekSchedule` — só conhece `ScheduledJob`. | Novo | 1 dia |
| ❌ | Isolamento de falha: falha no job de um tenant não afeta outros — DLQ por tenant com retry configurável | Novo | 1 dia |
| ❌ | Reagendamento dinâmico: `TenantScheduler.reschedule(tenantId, jobId, newCron)` sem restart da aplicação | Novo | 0.5 dia |

```java
// Interface pública do TenantScheduler — o ArchFlow expõe, o VendaX usa
public interface TenantScheduler {
    void schedule(ScheduledJob job);
    void reschedule(String tenantId, String jobId, String newCron);
    void cancel(String tenantId, String jobId);
    List<ScheduledJob> listByTenant(String tenantId);
}

public record ScheduledJob(
    String tenantId,
    String jobId,
    String cronExpression,
    String agentId,
    Map<String, Object> payload   // o VendaX coloca o que precisar aqui
) {}
```

---

## 3. AgentTriggerRuntime — Os Quatro Tipos de Gatilho

O ArchFlow precisa ser capaz de acionar agentes pelos quatro tipos de gatilho como infraestrutura genérica. O que dispara dentro de cada tipo é responsabilidade do produto (VendaX).

### 3.1 Gatilho Tipo 1 — Conversacional

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ⚠️ | `ArchflowEvent` com `tenantId` — evento conversacional carrega o tenant desde a origem | Extensão | 0.5 dia |
| ⚠️ | `ConversationManager`: particionamento por `tenantId` no registro de subscribers | Extensão | 1 dia |
| ❌ | Endpoint genérico: `POST /archflow/events/message` — recebe evento com `tenantId + sessionId + payload`, valida JWT, aciona o orquestrador registrado para o tenant | Novo | 1 dia |
| ⚠️ | **Corrigir GAP:** `ChatMemory` não é restaurado no resume de flows — ao retomar conversa, carregar histórico Redis pela `sessionKey` antes de invocar o agente | Correção | 1 dia |

---

### 3.2 Gatilho Tipo 2 — Temporal

Implementado via `TenantScheduler` (seção 2). O motor dispara um `ScheduledJob` — o VendaX registra seus jobs de negócio usando essa infraestrutura.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | Quando `TenantScheduler` dispara um job: montar `ExecutionContext` com `tenantId + sessionId + payload` e acionar o agente configurado | Novo | 0.5 dia |

---

### 3.3 Gatilho Tipo 3 — Threshold

O motor precisa de infraestrutura genérica de monitoramento. O que é monitorado (KPIs de churn, inadimplência etc.) é definido pelo VendaX via `MetricProvider`.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ⚠️ | `ThresholdAnomalyDetector`: tornar genérico — receber `ThresholdRule { tenantId, metricId, condition, value }` em vez de valores hardcoded. O VendaX registra suas regras como `ThresholdRules`. | Extensão | 2 dias |
| ⚠️ | `MonitoringAgent`: adicionar `tenantId`. Receber métricas via interface `MetricProvider` que o VendaX implementa. | Extensão | 1 dia |
| ❌ | Quando threshold é atingido: montar `ExecutionContext` e acionar o agente configurado para aquela `ThresholdRule` | Novo | 1 dia |

```java
// Interface genérica — o ArchFlow define, o VendaX implementa
public interface MetricProvider {
    double getMetric(String tenantId, String metricId, Map<String, Object> context);
}

public record ThresholdRule(
    String tenantId,
    String ruleId,
    String metricId,
    String condition,   // ex: "> 0.20" ou "< 0.70"
    double value,
    String agentId      // qual agente acionar quando atingido
) {}
```

---

### 3.4 Gatilho Tipo 4 — Demanda

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | Endpoint genérico: `POST /archflow/agents/{agentId}/invoke` — recebe `tenantId`, `sessionId`, `payload`. Aciona o agente diretamente. | Novo | 1 dia |
| ❌ | `AgentInvocationQueue`: fila interna para acionamentos agente-para-agente sem bloquear o chamador | Novo | 2 dias |
| ❌ | Controle de recursão: profundidade máxima de invocação configurável — impedir loops infinitos | Novo | 1 dia |

---

## 4. Protocolo Human-in-the-Loop

Suspender um fluxo aguardando decisão humana é responsabilidade do **motor** — qualquer produto de IA precisa disso. O que o humano aprova (um `QUOTE`, uma `AI_SUGGESTION`) é do VendaX. O motor provê a infraestrutura de `suspend/resume` com `AWAITING_APPROVAL`.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ⚠️ | Estender `SUSPEND/RESUME` existentes com modo `AWAITING_APPROVAL`: agente suspende o fluxo após gerar uma proposta, aguarda decisão com timeout configurável | Extensão | 2 dias |
| ❌ | `HumanDecisionEvent`: evento genérico `{ requestId, tenantId, decision: APPROVED\|REJECTED\|EDITED, editedPayload? }` — o VendaX publica, o motor retoma | Novo | 1 dia |
| ❌ | `ApprovalRegistry`: mantém mapeamento `requestId → FlowState` suspenso para retomada correta | Novo | 1 dia |
| ❌ | Timeout de aprovação: se nenhuma decisão chegar em X segundos (configurável por tenant), retomar com decisão default | Novo | 1 dia |
| ❌ | `LearningCallback`: interface que o produto implementa para receber notificação de `REJECTED/EDITED` — o motor chama o callback, o produto decide o que aprender | Novo | 1 dia |

```java
// Interfaces genéricas que o ArchFlow expõe
public interface LearningCallback {
    void onRejected(String tenantId, String requestId, Object proposal);
    void onEdited(String tenantId, String requestId, Object original, Object edited);
}

// Evento publicado pelo VendaX para retomar o fluxo suspenso
public record HumanDecisionEvent(
    String requestId,
    String tenantId,
    Decision decision,    // APPROVED | REJECTED | EDITED
    Object editedPayload  // preenchido quando EDITED
) {}
```

---

## 5. Streaming de Payloads Ricos

O ArchFlow já tem streaming de eventos. O VendaX precisa enviar objetos ricos (`AI_SUGGESTION`, `QUOTE`, `ALERT`) pelo canal do vendedor. O motor precisa suportar streaming de payloads arbitrários — o que vai no payload é do VendaX.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ⚠️ | Adicionar tipo `PAYLOAD` ao `ArchflowEvent` — permite streaming de objeto estruturado arbitrário. O VendaX usa para enviar `RichObjects` ao canal do vendedor. | Extensão | 1 dia |
| ⚠️ | `EventStreamRegistry`: garantir particionamento por `tenantId` — streams de um tenant não vazam para subscribers de outro | Extensão | 1 dia |
| ❌ | Endpoint SSE por tenant: `GET /archflow/stream/{tenantId}/{sessionId}` — Linktor e dashboard subscrevem aqui para receber eventos em tempo real | Novo | 1 dia |

---

## 6. Melhorias na Infraestrutura de Memória

O ArchFlow já tem Brain Sentry integrado e `EpisodicMemory`. As melhorias aqui são de infraestrutura do motor — não de política de memória (o que gravar e quando é responsabilidade dos agentes do VendaX).

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ⚠️ | **Corrigir GAP:** `ChatMemory` não é restaurado no resume de flows — carregar histórico Redis pela `sessionKey` antes de invocar o agente ao retomar | Correção | 1 dia |
| ✅ | `BrainSentryInterceptor` (order=5): validar que propaga `tenantId` em todas as operações de escrita no grafo | Validação | 0.5 dia |
| ❌ | Interface `MemoryProvider`: contrato genérico que produtos implementam para injetar contexto adicional no início de cada execução. O VendaX implementa para carregar `PlaybookConfig` e perfil do vendedor. | Novo | 1 dia |

```java
// Interface genérica — o ArchFlow define, o VendaX implementa
public interface MemoryProvider {
    Map<String, Object> loadContext(String tenantId, String sessionId);
}
```

---

## 7. OpenRouter como Provider de LLM

O ArchFlow tem adapters para OpenAI e Anthropic. OpenRouter é um adapter adicional — capacidade do motor, não do VendaX.

| Status | O que implementar | Tipo | Esforço |
|---|---|---|---|
| ❌ | `OpenRouterAdapter`: via `LangChainAdapterFactory` — API compatível com OpenAI, configurar `base_url: https://openrouter.ai/api/v1` e headers de autenticação | Novo | 1 dia |
| ❌ | Seleção de modelo por tenant e por agente via `variables` do `ExecutionContext` — o adapter resolve o modelo antes de chamar o LLM | Novo | 1 dia |
| ❌ | Fallback para stack local via Ollama: se OpenRouter indisponível, redirecionar para endpoint local configurado — sem código no VendaX, só config no ArchFlow | Novo | 1 dia |

---

## 8. Resumo — Backlog do ArchFlow para o VendaX

Tudo listado abaixo é responsabilidade do **motor genérico**.
Lógica de negócio (`PlaybookConfig`, `TaskEngine`, agentes, tools) está no RFC-006.

| Área | Status | Principal entrega | Tipo | Dias |
|---|---|---|---|---|
| Multi-tenancy `ExecutionContext` | ❌ | `record` imutável com `tenantId` + `variables` genérico | Novo | 4 |
| Multi-tenancy `ChatMemory` | ❌ | Chave Redis com prefixo `tenantId` | Mudança | 2 |
| Multi-tenancy `EpisodicMemory` | ⚠️ | `tenantId` em todos os métodos da interface | Mudança | 2 |
| Multi-tenancy `FlowState/Repo` | ❌ | `StateRepositoryJdbc` com `tenant_id` | Novo | 3 |
| Multi-tenancy `ConversationManager` | ⚠️ | Particionamento de subscribers por tenant | Extensão | 2 |
| `TenantScheduler` | ❌ | Quartz com jobs dinâmicos por tenant | Novo | 5 |
| Gatilho Tipo 1 — entrada | ❌ | Endpoint `POST /archflow/events/message` | Novo | 1 |
| Gatilho Tipo 3 — `ThresholdRule` | ⚠️ | `ThresholdAnomalyDetector` genérico com `MetricProvider` | Extensão | 3 |
| Gatilho Tipo 4 — Demanda | ❌ | `AgentInvocationQueue` + endpoint `invoke` | Novo | 3 |
| Human-in-the-Loop | ⚠️ | `AWAITING_APPROVAL` + `ApprovalRegistry` + `LearningCallback` | Extensão | 5 |
| Streaming de payloads ricos | ⚠️ | Tipo `PAYLOAD` no evento + SSE endpoint por tenant | Extensão | 3 |
| Memória — `MemoryProvider` | ❌ | Interface genérica para injeção de contexto | Novo | 1 |
| `OpenRouterAdapter` | ❌ | `LangChainAdapterFactory` para OpenRouter + fallback local | Novo | 3 |
| **TOTAL ESTIMADO** | | | | **~37 dias** |

> Com 2 devs: estimativa de **3–4 semanas** em paralelo com o desenvolvimento do VendaX (RFC-006).
> As áreas de multi-tenancy têm dependência interna entre si mas podem ser trabalhadas por diferentes devs simultaneamente.
> A **Fase 1 do VendaX** (domain + playbook) pode começar em paralelo sem depender do ArchFlow estar pronto.

---

## Apêndice — O que NÃO pertence ao ArchFlow

Para evitar regressões futuras, esta lista documenta explicitamente o que foi removido do escopo do ArchFlow nesta revisão:

| Conceito | Onde fica | Motivo |
|---|---|---|
| `PlaybookConfig` | `vendax-playbook` | Conceito de food distribution — não existe em outros produtos |
| `VendorProgressionStep` | `vendax-domain` | Lógica comercial do Vendedor 4.0 |
| `IndicatorThreshold` | `vendax-domain` | KPIs específicos de distribuidoras |
| `TaskEngine` / `VendaxTask` | `vendax-engine` | Ciclo de vida de tarefas de vendas |
| `NexusOrchestrator` | `vendax-engine` | Configuração do supervisor para vendedores |
| `ArgosOrchestrator` | `vendax-engine` | Configuração do supervisor para gestão |
| Os 14 agentes | `vendax-agents` | Lógica de negócio de sales copilot |
| Todas as MCP tools | `vendax-tools` | Contratos específicos do ecossistema VendaX |
| `RichObject` (QUOTE, ALERT etc.) | `vendax-conversation` | Protocolo de UI específico do VendaX |
| `KpiEvaluator` / `AlertDispatcher` | `vendax-engine` | Lógica de monitoramento de negócio |
