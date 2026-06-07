# Design 0003 — Dynamic Orchestration: primitivos, budget e nós de fluxo

Implementação concreta do [ADR-0002](../adr/0002-dynamic-orchestration.md). Foco em
assinaturas Java, pontos de extensão e mapeamento ao código existente. Tudo sobe
para o **substrato** (genérico); a lógica de domínio fica no fluxo/produto.

## 1. Por que isto existe

O blog *dynamic workflows* descreve um padrão repetível — decompor → fan-out →
verificar → convergir, resumível e orçado. O archflow já tem motor resumível,
paralelismo, router dinâmico e observabilidade; falta a **camada que orquestra**
esses primitivos em runtime e o **orçamento com enforcement**. Este design define
essa camada como uma API pequena e determinística (control-flow em código,
conteúdo dirigido pelo modelo).

## 2. O Orchestrator (núcleo — `archflow-core` / `archflow-agent`)

```java
package br.com.archflow.orchestration;

/**
 * Orquestrador determinístico de workflows dinâmicos. O control-flow é código;
 * só a DECOMPOSIÇÃO e os ACHADOS são dirigidos pelo modelo. Construído sobre a
 * mesma infra do DefaultFlowEngine (virtual-threads + Semaphore) e persistido
 * via StateManager para resume.
 */
public interface Orchestrator {

    /** Decompõe um objetivo em subtarefas (model-driven). */
    <T> Plan<T> plan(Goal goal, PlanSpec<T> spec, ExecutionContext ctx);

    /** Map paralelo: roda cada item por um worker, respeitando budget/concorrência. */
    <I, O> List<Result<O>> fanOut(List<I> items, Worker<I, O> worker, ExecutionContext ctx);

    /** Verificação adversarial: K verificadores independentes; mantém se survives(policy). */
    <F> Verdict verify(F finding, VerifyPolicy policy, ExecutionContext ctx);

    /** Loop até convergir (ou estourar budget/rounds). O round produz incrementos. */
    <S> S loopUntil(S seed, Round<S> round, ConvergePolicy policy, ExecutionContext ctx);
}
```

Tipos de apoio (records):

```java
public record Goal(String description, Map<String,Object> inputs) {}

/** Como decompor: prompt + (opcional) tipo de worker a rotear via catálogo. */
public record PlanSpec<T>(String decomposePrompt, Class<T> itemType,
                          ComponentType workerType, int maxItems) {}

public record Plan<T>(List<T> items, String rationale) {}

@FunctionalInterface
public interface Worker<I, O> { Result<O> apply(I item, ExecutionContext ctx); }

public record Result<O>(O value, boolean ok, String error, ExecutionMetrics metrics) {}

/** k verificadores; aprova se >= minAgree não-refutam. lens = perspectivas distintas. */
public record VerifyPolicy(int voters, int minAgree, List<String> lenses) {}
public record Verdict(boolean confirmed, int agree, int refute, double confidence) {}

/** Convergência: para quando dryRounds rounds seguidos sem novidade, OU quality>=t. */
public record ConvergePolicy(int maxRounds, int dryRounds, double qualityThreshold) {}

@FunctionalInterface
public interface Round<S> { RoundOutcome<S> run(S state, int round, ExecutionContext ctx); }
public record RoundOutcome<S>(S state, boolean producedNew, double quality) {}
```

### Seleção dinâmica de worker

`plan`/`fanOut` escolhem o agente via o router que **já existe** —
`ComponentQueryRouter.route(query, type)` / `rank(...)` — quando o `PlanSpec`
pede `workerType` em vez de um agente fixo. Isso reaproveita `/api/catalog/route`
e o scoring por keywords do ADR-0001.

### Reuso de infra (nada de motor novo)

| Necessidade | Reusa |
|---|---|
| Concorrência/backpressure | `Semaphore` + virtual-threads do `DefaultFlowEngine` |
| Estado/resume | `StateManager.saveState/loadState`, `FlowState.executionPaths` |
| Seleção de worker | `ComponentQueryRouter` |
| Verificação | `ConfidenceScorer` + `GuardrailChain` |
| Modelo barato/caro por tier | `LLMConfigResolver` (worker patch vs. orchestrator patch) |
| Tracing/progresso | `ArchflowTracer` (`startAgent`/`startTool`) + feed LiveEvents |

## 3. Budget (D5) — teto com enforcement

Estende a config resolvida e o contexto, sem quebrar o resolver existente:

```java
// novo record
public record Budget(Long maxTokens, Double maxCostUsd) {
    public static final Budget UNLIMITED = new Budget(null, null);
}

// ResolvedLLMConfig ganha um campo opcional (default UNLIMITED)
public record ResolvedLLMConfig(/* …existentes… */, Budget budget) { }
```

- **Onde mora o gasto:** `ExecutionContext` carrega um `BudgetLedger` por run
  (`spent()`/`remaining()`), alimentado pelas mesmas métricas que já existem
  (`ExecutionMetrics.tokensUsed/estimatedCost`).
- **Enforcement:** antes de cada chamada de subagente, `fanOut`/`loopUntil`
  checam `ledger.remaining()`; ao zerar, **abortam o fan-out** (em vez de só
  registrar custo pós-fato). O `LLMConfigResolver` propaga o `Budget` resolvido.
- **Governança:** o `Budget` e as políticas `VerifyPolicy`/`ConvergePolicy` entram
  no documento de governança versionada (ADR-0001 D3), escopáveis por tenant.

## 4. Nós de orquestração no `Flow` (D6 — expressar no designer)

Hoje `StepType` (`archflow-model/.../flow/StepType.java`) é fechado:
`{ASSISTANT, AGENT, TOOL, CHAIN, CUSTOM}`. Adicionar:

```java
public enum StepType {
    ASSISTANT, AGENT, TOOL, CHAIN, CUSTOM,
    ORCHESTRATE,  // planner: emite sub-fluxo em runtime
    FAN_OUT,      // map paralelo sobre lista produzida em runtime
    VERIFY,       // verificação adversarial
    LOOP_UNTIL    // convergência / quality-gate
}
```

Config por nó (no JSON do step / designer) mapeia 1:1 nos records da §2 — ex.:

```yaml
- id: audit
  type: ORCHESTRATE
  config:
    decomposePrompt: "Liste os arquivos a auditar para vazamento de segredo"
    worker: { mode: auto, workerType: AGENT }   # auto = ComponentQueryRouter
    verify: { voters: 3, minAgree: 2, lenses: [correctness, security] }
    converge: { maxRounds: 4, dryRounds: 2, qualityThreshold: 0.9 }
    budget: { maxTokens: 2000000 }
```

### Materialização dinâmica do grafo

O nó `ORCHESTRATE` chama `Orchestrator.plan(...)`; cada subtarefa vira uma
`ExecutionPath` nova adicionada ao `FlowState` em runtime (o `FlowState` já modela
`executionPaths` com `parallelBranches`). Assim o grafo **cresce dinamicamente**,
fica persistido para resume e **replayable/inspecionável** na observabilidade. O
designer renderiza esses nós como "containers" (o conteúdo expande no run).

## 5. Streaming ao vivo (D7)

Cada transição (plano gerado, item despachado, verificação, round/convergência)
emite um evento no feed **LiveEvents (SSE)** que a UI já consome
(`LiveEventsPage`). A árvore dinâmica aparece ao vivo — a "visibilidade escalável"
de runs de horas/dias, sem polling.

## 6. O que fica de fora (harness — fica no fluxo/produto)

- *O que* decompor e *como* verificar um achado **naquele domínio** (regras de
  match, thresholds, fontes de verdade). Ex.: `gestor-rq` de-para de catálogo →
  `fanOut(candidatos, matchAgent) → verify(par, policy)`, mas Jaccard/pg_trgm e
  thresholds ficam no produto.
- Decisão de orçamento e SLA por negócio (valores), embora o *mecanismo* de
  enforcement seja do substrato.

## 7. Sequência sugerida

1. **P0** — `Orchestrator` + 4 primitivos + `Budget`/`BudgetLedger`/enforcement;
   refazer `AgentSupervisorTemplate` como composição dos primitivos (prova viva).
2. **P1** — novos `StepType` + config + materialização dinâmica no `FlowState`;
   nós no designer; streaming LiveEvents; políticas na governança; expor
   `/api/catalog/route` + keywords na UI.
3. **P2** — adapters de harness em `gestor-rq`/`integrall`.

## 8. Status de implementação

**PROPOSTO** — nada implementado ainda. Este design define o contrato; a próxima
etapa é o P0 (Orchestrator + primitivos + budget) atrás de testes unitários,
reusando virtual-threads/`StateManager`/`ComponentQueryRouter` existentes.
