# ADR-0002 — Dynamic Orchestration: orquestração dinâmica multi-agente sobre o substrato

- **Status:** Proposto
- **Data:** 2026-06-06
- **Decisores:** Edson Martins
- **Contexto de origem:** análise do conceito *dynamic workflows* do Claude Code
  (https://claude.com/blog/introducing-dynamic-workflows-in-claude-code) cruzada
  com o mapeamento do motor de execução atual do archflow.
- **Empilha sobre:** [ADR-0001 — Agent Runtime Substrate](0001-agent-runtime-substrate.md)
  (D1 agente como primitivo, D2 `LLMConfigResolver`, D3 governança versionada).

## Sumário

O archflow é hoje **flow-first estático**: um humano desenha o DAG
(`Flow → FlowStep`, designer visual / YAML) e o `DefaultFlowEngine` percorre os
steps. Isso cobre processos determinísticos, mas não cobre a classe de problemas
que o Claude Code chama de *dynamic workflows*: trabalho de larga escala onde a
**decomposição é decidida em runtime pelo modelo**, executada em **fan-out
paralelo**, **verificada adversarialmente** e **iterada até convergir**, com
**estado resumível** e **orçamento de custo**.

Esta ADR adiciona uma **camada genérica de orquestração dinâmica** ao substrato,
com quatro decisões P0/P1:

- **D4.** Um **Orchestrator runtime** com quatro primitivos determinísticos —
  `plan` (decompor), `fanOut` (map paralelo), `verify` (verificação adversarial),
  `loopUntil` (convergência) — construídos sobre a infra que já existe
  (virtual-threads + `Semaphore`, `StateManager`, `ComponentQueryRouter`).
- **D5.** **Orçamento** (tokens/custo) escopável por run/tenant, com *enforcement*
  (não só métrica pós-fato), estendendo `ResolvedLLMConfig`/`ExecutionContext`.
- **D6.** Novos **nós de orquestração de alto nível** no modelo de `Flow`
  (`ORCHESTRATE`, `FAN_OUT`, `VERIFY`, `LOOP_UNTIL`) + um *planner* que **materializa
  sub-fluxo em runtime** nas `ExecutionPath` do `FlowState` — para que o usuário
  expresse padrões dinâmicos no designer, sem código.
- **D7.** Verificação/convergência como **política governada** + **streaming ao
  vivo** da árvore dinâmica via o canal LiveEvents (SSE) que a UI já consome.

## Contexto

### O que existe hoje (fundamentado)

- **Motor resumível:** `DefaultFlowEngine` (virtual threads + `Semaphore` de
  backpressure, timeout por flow), `FlowEngine.resumeFlow/pause/cancel`,
  `StateManager.saveState/loadState`, `FlowState.executionPaths` (com
  `parallelBranches`). → **estado e paralelismo já existem.**
- **Roteamento dinâmico de componente:** `ComponentQueryRouter.route(query,type)`
  / `rank(...)` (score por keywords/capabilities/tags), exposto em
  `/api/catalog/route`. → **seleção de "melhor agente para a próxima tarefa" já é
  possível**, mas só no nível de API, não dentro da execução.
- **Primitivo de agente:** `AIAgent.planActions/makeDecision` e o
  `ConversationalAgent` (loop guardrail-in → LLM → tool* → guardrail-out, com
  `maxIterations` **fixo**).
- **Escopo de modelo/custo:** `LLMConfigResolver` (cadeia
  step>agent>flow>tenant>platform). → permite **modelo barato p/ workers, caro p/
  orquestrador/verificador**.
- **Governança/guardrails:** substrato do ADR-0001 (`GovernanceSettings`,
  `GuardrailChain`), com `RateLimitSettings` **definido mas com enforcement
  adiado ao produto**.
- **Observabilidade:** `ArchflowTracer`/`ArchflowMetrics` + feed **LiveEvents
  (SSE)** já consumido pela UI. → a "visibilidade escalável" de runs longos.
- **Stub de orquestração:** `AgentSupervisorTemplate` (parâmetros
  `enableDecomposition`, `maxSubTasks`, `parallelExecution`, `qualityThreshold`)
  — **é template estático, não há loop dinâmico real.**

### O gap

Não há uma camada que **orquestre** esses primitivos em runtime: decompor um
objetivo, distribuir em paralelo, verificar e iterar até convergir. E não há
**orçamento com enforcement** — `ExecutionMetrics.tokensUsed/estimatedCost` são
coletados pós-fato, sem teto. Justamente o caveat #1 do blog ("dynamic workflows
podem consumir substancialmente mais tokens").

### A lição do blog (reenquadrada)

*Dynamic workflow* não é "o LLM improvisa o grafo". É um **harness determinístico
em código** (loop, fan-out, verify, converge) onde **só o conteúdo/decomposição é
dirigido pelo modelo**. Isso é exatamente o tipo de mecanismo genérico que, pela
fronteira do ADR-0001, **sobe para o archflow**.

### Fronteira de papéis (confirmada)

- **Sobe pro archflow (substrato):** o mecanismo genérico — primitivos
  `plan/fanOut/verify/loopUntil`, orçamento, resume, streaming de progresso,
  seleção dinâmica de worker via catálogo.
- **Fica no fluxo/produto (harness):** a lógica de domínio — *o que* decompor,
  *como* verificar um "achado" naquele negócio, critérios de convergência e o
  orçamento. Ex.: de-para de catálogo Oracle/Consinco no `gestor-rq` vira um
  `fanOut → verify` governado, mas as regras de match (Jaccard/pg_trgm,
  thresholds) ficam no produto.

## Decisão

### D4 — Orchestrator runtime + 4 primitivos determinísticos

Introduzir, em `archflow-core` (motor) / `archflow-agent`, uma API de orquestração
determinística — não improvisada pelo modelo:

- **`plan(goal, ctx)`** → um **agente orquestrador** (reusa `AIAgent.planActions`
  e, p/ escolher workers, `ComponentQueryRouter`) devolve uma lista tipada de
  subtarefas. O engine materializa cada uma como `ExecutionPath`/branch dinâmico.
- **`fanOut(items, worker)`** → map paralelo sobre a infra de virtual-threads +
  `Semaphore` que o `DefaultFlowEngine` já usa.
- **`verify(finding, policy)`** → K verificadores adversariais independentes
  (reusa `ConfidenceScorer` + guardrails); confirma se a maioria não refuta.
- **`loopUntil(predicate)`** → generaliza o `maxIterations` fixo numa **política
  de convergência** (loop-until-dry / quality-gate / budget-bound).

O control-flow é **código determinístico**; o conteúdo (subtarefas, achados) é do
modelo. Cada primitivo é instrumentado no `ArchflowTracer`.

### D5 — Orçamento escopável + enforcement

Adicionar um **budget** (teto de tokens e/ou custo) escopável por run e por tenant,
carregado no `ExecutionContext` e considerado pelo `LLMConfigResolver`/camada de
métricas: ao atingir o teto, novas chamadas de subagente **falham/abortam** o
fan-out em vez de estourar custo. É a contraparte governada do caveat do blog —
e a peça que faltava em `RateLimitSettings`/`ExecutionMetrics`.

### D6 — Nós de orquestração no `Flow` (expressar no designer)

Estender o modelo de `Flow` para que o **usuário componha padrões dinâmicos sem
código**. Hoje `StepType` é enum fechado `{ASSISTANT, AGENT, TOOL, CHAIN, CUSTOM}`;
adicionar nós de alto nível: **`ORCHESTRATE`** (planner), **`FAN_OUT`/`MAP`**
(sobre lista produzida em runtime), **`VERIFY`** (adversarial), **`LOOP_UNTIL`**
(convergência). O usuário desenha a **forma** e fornece a **política** como config
do nó (prompt de decomposição, worker = agente fixo ou `auto` via router, nº de
verificadores + regra, critério de convergência, budget). O planner **emite um
sub-fluxo em runtime** materializado nas `ExecutionPath` — o grafo cresce
dinamicamente e fica inspecionável/replayable na observabilidade.

### D7 — Verificação/convergência governadas + streaming ao vivo

As políticas de `verify`/`loopUntil` e o budget entram no documento de
**governança versionada** (ADR-0001 D3) — auditáveis e escopáveis por tenant. O
progresso da árvore dinâmica (subtarefas, fan-out, verificações, convergência) é
**emitido pelo feed LiveEvents (SSE)** que a UI já consome, dando visibilidade de
runs longos sem polling.

## Consequências

### Positivas

- archflow ganha a classe "dynamic workflow" **reusando** motor, paralelismo,
  resume, router e observabilidade já existentes — pouca infra nova.
- **Diferencial vs. Claude Code:** multi-tenant, **orçado** e **governado**. O
  caveat de custo do blog vira controle de 1ª classe.
- Custo otimizável por design via `LLMConfigResolver` (worker barato, juiz caro).
- O usuário **evolui o próprio harness no designer** (D6), preservando a fronteira
  framework-vs-harness.

### Negativas / riscos

- Complexidade real no motor (concorrência, cancelamento parcial, materialização
  dinâmica de path). Mitigação: primitivos pequenos e testáveis; reaproveitar o
  `Semaphore`/virtual-threads existentes.
- Custo/tokens: mitigado por D5 (budget com enforcement) e gates de confirmação.
- Não-determinismo de decomposição dificulta replay exato. Mitigação: persistir
  no `FlowState` o plano e os seeds para reexecução inspecionável.

### Neutras

- `AgentSupervisorTemplate` deixa de ser stub e passa a ser uma composição dos
  novos primitivos (ou é aposentado em favor dos nós de D6).

## Alternativas consideradas

1. **Deixar tudo no produto (harness):** repetiria a divergência gestor/integrall
   que o ADR-0001 combateu. Rejeitada.
2. **Orquestrador 100% LLM-driven (o modelo decide o control-flow):** frágil,
   caro e não-replayable. Rejeitada em favor de control-flow determinístico +
   conteúdo model-driven.
3. **Só expor primitivos em código (sem nós no designer):** perderia o público
   no-/low-code do designer visual e o pedido explícito de "melhorar no fluxo".
   Rejeitada — D6 mantém ambos.

## Plano de adoção (ordem)

- **P0 (substrato):** D4 (4 primitivos + Orchestrator) sobre virtual-threads +
  `StateManager`; D5 (budget + enforcement). Worker-selection via
  `ComponentQueryRouter`. Sem UI ainda — API Java + 1 template de exemplo
  (refazer `AgentSupervisorTemplate` sobre os primitivos).
- **P1 (fluxo + visibilidade):** D6 (novos `StepType`/nós no designer + planner
  que materializa sub-fluxo) e D7 (streaming LiveEvents + políticas na governança).
  Expor `keywords`/`/api/catalog/route` na UI (follow-up já pendente do ADR-0001).
- **P2 (harness nos produtos):** adapters em `gestor-rq`/`integrall` usando o
  substrato (ex.: de-para de catálogo como `fanOut → verify` governado).

## Referências

- [ADR-0001 — Agent Runtime Substrate](0001-agent-runtime-substrate.md)
- [Design 0003 — Dynamic Orchestration (implementação)](../design/0003-dynamic-orchestration.md)
- [Design 0004 — Workflow execution wiring + orchestration nodes (D6)](../design/0004-workflow-execution-and-orchestration-nodes.md)
- [Design 0005 — Async flow execution (engine wiring, live streaming, resume)](../design/0005-async-flow-execution.md)
- Claude Code — *Introducing dynamic workflows*:
  https://claude.com/blog/introducing-dynamic-workflows-in-claude-code
- Código-base atual: `DefaultFlowEngine`, `StateManager`, `FlowState`,
  `ComponentQueryRouter`, `ConversationalAgent`, `LLMConfigResolver`,
  `AgentSupervisorTemplate`, `ArchflowTracer`/`ArchflowMetrics`.
