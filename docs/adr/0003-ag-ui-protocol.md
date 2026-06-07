# ADR-0003 — Adotar AG-UI como protocolo agente↔UI (e CopilotKit como camada de UI)

- **Status:** Proposto
- **Data:** 2026-06-07
- **Decisores:** Edson Martins
- **Contexto de origem:** análise do CopilotKit (https://docs.copilotkit.ai/) e do
  protocolo aberto **AG-UI** (Agent-User Interaction, https://github.com/ag-ui-protocol/ag-ui),
  cruzada com a infra de streaming agente↔UI que o archflow já possui.
- **Empilha sobre:** [ADR-0001 — Agent Runtime Substrate](0001-agent-runtime-substrate.md),
  [ADR-0002 — Dynamic Orchestration](0002-dynamic-orchestration.md) (streaming D7,
  LiveEvents, execução async / design-0005).

## Sumário

O archflow já transmite eventos agente↔UI por um **protocolo proprietário**
(`ArchflowEvent` sobre SSE em `/archflow/stream/{tenantId}/{sessionId}`, hub
`EventStreamRegistry`, consumido pelo `ArchflowEventStream`/`LiveEventsPage`). O
**AG-UI** padroniza exatamente isso — um stream único de eventos JSON sobre HTTP,
com tipos para mensagens, tool calls, *state deltas* e lifecycle — e virou um
**padrão emergente com SDK Java** adotado por Google, Microsoft, Amazon (Bedrock
AgentCore) e Oracle, além de LangGraph/CrewAI/Mastra.

Esta ADR decide **adotar AG-UI como o protocolo de fio agente↔UI do archflow**
(de forma aditiva, sem quebrar o stream nativo) e **CopilotKit como camada de UI
React** (componentes + generative UI + frontend actions) no `archflow-ui`. O
motor, a orquestração dinâmica, a governança e o multi-tenancy **permanecem do
archflow** — AG-UI/CopilotKit entram como **protocolo + pele de UI**, não como
motor de agentes.

## Contexto

### O que existe hoje (fundamentado)

- **Stream agente↔UI proprietário:** `StreamController` (SSE por tenant/session),
  envelope `ArchflowEvent` (`domain`, `type`, `id`, `timestamp`, `executionId`,
  `tenantId`, `data`, `metadata`), `EventStreamRegistry.broadcast(executionId, …)`
  + `addGlobalListener`. `RegistryFlowLifecycleListener` já publica lifecycle de
  flow/step (wired no engine em design-0005 step 3).
- **Taxonomia de eventos quase AG-UI-shaped** (`ArchflowEventType`):
  `FLOW_STARTED/COMPLETED/FAILED`, `STEP_STARTED/COMPLETED/FAILED/SKIPPED`,
  `MESSAGE`, `DELTA`, `THINKING`, `REFLECTION`, `VERIFICATION`, `TOOL_START`,
  `RESULT`, `TOOL_ERROR`, `PROGRESS`, `SUSPEND`/`FORM`/`RESUME`/`CANCEL`,
  `HEARTBEAT`, etc. `ArchflowDomain`: CHAT, THINKING, TOOL, AUDIT, INTERACTION,
  SYSTEM, PAYLOAD, FLOW.
- **Estado materializado:** `FlowState.executionPaths` (árvore dinâmica do step 4),
  legível via `GET /api/executions/{id}` e renderizada na `ExecutionDetailPage`.
- **HITL:** approval nodes, `AWAITING_APPROVAL`, `requestApproval/submitApproval`,
  e `resume`/`cancel` endpoints (design-0005 step 5).
- **Assist:** endpoints `nl-to-flow`, `suggest-mapping`, `explain-error` — sem,
  porém, uma camada conversacional in-app rica.

### O que o AG-UI padroniza

Stream único de eventos JSON sobre HTTP (+ canal binário opcional). Tipos
canônicos: lifecycle `RUN_STARTED/RUN_FINISHED/RUN_ERROR`, `STEP_STARTED/STEP_FINISHED`;
texto `TEXT_MESSAGE_START/CONTENT/END` (+CHUNK); tool `TOOL_CALL_START/ARGS/END/RESULT`;
estado `STATE_SNAPSHOT/STATE_DELTA/MESSAGES_SNAPSHOT`; reasoning `REASONING_*`;
especiais `RAW`, `CUSTOM`. Um run é iniciado por `RunAgentInput`
(`messages`, `state`, `tools`, `threadId`, `runId`) e delimitado por
`RUN_STARTED` … `RUN_FINISHED`/`RUN_ERROR`. **Há SDK Java** — compatível com o
backend Spring do archflow.

### O gap / a oportunidade

O archflow reinventou (bem) um protocolo que agora tem padrão aberto. Adotá-lo dá
**interoperabilidade** (qualquer cliente AG-UI consome runs do archflow; o archflow
consome agentes AG-UI de terceiros) e **componentes React de graça** (CopilotKit),
sem reescrever o motor. O mapeamento é quase 1:1.

## Decisão

### D8 — AG-UI como protocolo de fio (aditivo)

Emitir eventos **AG-UI** a partir do mesmo hub (`EventStreamRegistry`), via uma
**camada de tradução** `ArchflowEvent → AG-UI` (usando o SDK Java AG-UI). Um
endpoint AG-UI-compliant expõe um run como stream de eventos AG-UI. O stream
nativo `ArchflowEvent` continua durante a transição (não-quebrante).

### D9 — Estado compartilhado = `FlowState`

Mapear `FlowState`/`executionPaths` (a árvore dinâmica) para
`STATE_SNAPSHOT`/`STATE_DELTA` do AG-UI — a UI (canvas/CopilotKit) recebe a árvore
crescendo como **shared state ao vivo**, não como eventos ad-hoc. Substitui o
polling da `ExecutionDetailPage` por sincronização de estado.

### D10 — CopilotKit React como camada de UI (não o runtime)

No `archflow-ui`: `CopilotChat/Sidebar` consumindo o stream AG-UI;
`useCopilotReadable` expõe o contexto do app (flow no canvas, nó selecionado,
catálogo); `useCopilotAction` expõe ações (rodar/cancelar/resumir workflow, editar
nó, explicar erro) **ligadas aos endpoints REST que já existem** e aos assist
(`nl-to-flow`/`suggest-mapping`/`explain-error`). Generative UI renderiza a árvore
dinâmica e cards de aprovação dentro do chat. **Não** adotar o runtime
Node/.NET/Python do CopilotKit.

### D11 — Fronteira preservada

Motor (`FlowEngine`), orquestração dinâmica (ADR-0002), governança/guardrails
(ADR-0001 D3) e budget/multi-tenancy **continuam do archflow** — são os
diferenciais. AG-UI é protocolo; CopilotKit é UI. Governança e budget podem ser
**expostos como `tools`/estado AG-UI** (ex.: o copiloto vê o orçamento restante).

## Mapeamento (indicativo)

| ArchflowEventType | AG-UI |
|---|---|
| `FLOW_STARTED` | `RUN_STARTED` |
| `FLOW_COMPLETED` / `FLOW_FAILED` | `RUN_FINISHED` / `RUN_ERROR` |
| `STEP_STARTED` | `STEP_STARTED` |
| `STEP_COMPLETED`/`FAILED`/`SKIPPED` | `STEP_FINISHED` (status no payload) |
| `MESSAGE` / `DELTA` (chat) | `TEXT_MESSAGE_START/CONTENT/END` (ou `*_CHUNK`) |
| `THINKING` / `REFLECTION` | `REASONING_MESSAGE_*` |
| `TOOL_START` / `RESULT` / `TOOL_ERROR` | `TOOL_CALL_START` / `TOOL_CALL_RESULT` |
| `PROGRESS` / `VERIFICATION` (orquestração) | `STATE_DELTA` (árvore) + `CUSTOM` (granular) |
| `executionPaths` (FlowState) | `STATE_SNAPSHOT` / `STATE_DELTA` |
| `SUSPEND` / `FORM` (HITL) | `CUSTOM` (ou `STATE_DELTA` "awaiting") |
| `RESUME` / `CANCEL` | lifecycle / `CUSTOM` |
| `TRACE`/`SPAN`/`METRIC`/`LOG` | fora-de-banda (observabilidade), não AG-UI |

## Consequências

### Positivas
- **Interoperabilidade**: runs do archflow consumíveis por clientes AG-UI
  (Google/MS/AWS/Oracle/LangGraph…); archflow consome agentes AG-UI externos.
- **Componentes React de graça** (CopilotKit) — menos UI bespoke para manter.
- Alinhamento a um **padrão emergente** com SDK Java (encaixa no Spring).
- Reusa o `EventStreamRegistry` e a taxonomia que já existem — pouca infra nova.
- Fecha o ciclo conversacional in-app sobre os assist endpoints já prontos.

### Negativas / riscos
- **Spec jovem** (AG-UI evolui) → isolar via a camada de tradução; não acoplar o
  domínio aos tipos AG-UI.
- **Descasamento de transporte**: AG-UI = request→SSE em uma resposta; archflow =
  fire-and-track (`/execute` retorna já) + subscribe separado. O endpoint AG-UI
  precisa reconciliar (POST que roda e faz stream, reusando o hub).
- **Custo de manter o tradutor** (dois formatos durante a transição).
- **Lock-in comercial**: usar só o protocolo aberto + componentes OSS; evitar o
  CopilotKit Cloud/runtime pago.

### Neutras
- O `LiveEventsPage` bespoke pode, no fim, ser substituído por componentes AG-UI.

## Alternativas consideradas
1. **Status quo (só `ArchflowEvent`)**: zero interop, toda UI bespoke. Rejeitada.
2. **A2UI (Google) no lugar de AG-UI**: menos tração hoje e sem ênfase em SDK
   Java; specs estão convergindo. Rejeitada por ora; reavaliar.
3. **Adotar o runtime CopilotKit (Node)**: descasa do backend Java e duplicaria o
   motor. Rejeitada — adotamos protocolo + UI, não runtime.

## Plano de adoção (ordem)

- **P0 (bridge + POC):** camada de tradução `ArchflowEvent → AG-UI` + 1 endpoint
  AG-UI que faz bridge de um run existente (reusando `EventStreamRegistry`); POC
  com `CopilotSidebar` no `archflow-ui` + 1 `useCopilotAction` ("rodar workflow").
- **P1 (estado + HITL):** `executionPaths` como `STATE_DELTA`; HITL
  (suspend/resume) sobre AG-UI; canvas readback dirigido por estado.
- **P2 (UI completa):** CopilotKit no designer (generative UI, frontend actions de
  edição de canvas, `useCopilotReadable` do flow); aposentar o stream bespoke.

## Referências
- [ADR-0001 — Agent Runtime Substrate](0001-agent-runtime-substrate.md)
- [ADR-0002 — Dynamic Orchestration](0002-dynamic-orchestration.md)
- [Design 0006 — AG-UI bridge (implementação)](../design/0006-ag-ui-bridge.md)
- CopilotKit: https://docs.copilotkit.ai/ · AG-UI: https://github.com/ag-ui-protocol/ag-ui
- Código atual: `ArchflowEvent`/`ArchflowEventType`/`ArchflowDomain`,
  `StreamController`, `EventStreamRegistry`, `RegistryFlowLifecycleListener`,
  `FlowState.executionPaths`.
