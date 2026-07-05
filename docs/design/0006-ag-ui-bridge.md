# Design 0006 — AG-UI bridge (translation layer + endpoint + POC)

Implements **ADR-0003 P0**: emit AG-UI events from archflow's existing event hub
and expose an AG-UI-compliant endpoint, so AG-UI/CopilotKit clients can drive and
observe archflow runs — reusing the streaming infra already in place
(design-0005 step 3), without touching the engine.

## 1. Por que isto existe

archflow already broadcasts run progress on `EventStreamRegistry` (native
`ArchflowEvent` over SSE). ADR-0003 decided AG-UI is the wire standard. The
minimal, non-breaking move is a **translation layer** + an **AG-UI endpoint** that
tees the existing broadcast into an AG-UI event stream. The engine, orchestration,
governance and the `RegistryFlowLifecycleListener` (already wired) are reused
as-is.

## 2. Transport reconciliation (the crux)

- **AG-UI shape:** client POSTs `RunAgentInput` (`messages`, `state`, `tools`,
  `threadId`, `runId`) and the **same response** streams events
  `RUN_STARTED … RUN_FINISHED/RUN_ERROR` (SSE). This is what CopilotKit expects.
- **archflow shape:** `POST /execute` returns immediately (`executionId`) and
  progress is on a **separate** SSE (`/archflow/stream/{tenantId}/{sessionId}`).

The bridge endpoint adopts the AG-UI shape by **running and streaming in one
request**: it starts the flow, subscribes to the hub for that run, translates
each event, and writes the SSE response until the run terminates.

```
POST /ag-ui/workflows/{id}     body: RunAgentInput        produces: text/event-stream
  ├─ executionId = runId ?: new          (flow id = executionId, as in /execute)
  ├─ ctx = DefaultExecutionContext(tenantId, "ag-ui", executionId, memory)
  │     seed ctx "input" from RunAgentInput.messages / .state
  ├─ register a per-request sink on EventStreamRegistry filtered by executionId
  │     (addGlobalListener + executionId filter, or createEmitter(executionId))
  ├─ emit RUN_STARTED
  ├─ flowEngine.execute(flow, ctx)        // async; events flow through the hub
  │     each ArchflowEvent ──AgUiEventMapper──▶ AG-UI event ──▶ SSE write
  ├─ on completion: emit RUN_FINISHED (or RUN_ERROR) + close the sink
  └─ (cancel/disconnect → flowEngine.cancel(executionId))
```

A second, even simpler endpoint can bridge an **already-running** execution
(`GET /ag-ui/runs/{executionId}` → SSE) for clients that started the run via the
existing `/execute`. Both reuse the same mapper and sink.

## 3. Translation layer

```java
package br.com.archflow.api.agui;

/** Maps a native ArchflowEvent to zero-or-more AG-UI events (design-0006). */
public interface AgUiEventMapper {
    List<BaseEvent> toAgUi(ArchflowEvent event);   // BaseEvent from the AG-UI Java SDK
}
```

Mapping (per ADR-0003 table), produced by `DefaultAgUiEventMapper`:

- `FLOW_STARTED → RUN_STARTED`; `FLOW_COMPLETED → RUN_FINISHED`;
  `FLOW_FAILED → RUN_ERROR`.
- `STEP_STARTED → STEP_STARTED`; `STEP_COMPLETED/FAILED/SKIPPED → STEP_FINISHED`
  (status in payload).
- `MESSAGE/DELTA` (chat domain) → `TEXT_MESSAGE_START/CONTENT/END` — the mapper
  tracks an open message id per `executionId` so deltas become `TEXT_MESSAGE_CONTENT`.
- `THINKING/REFLECTION → REASONING_MESSAGE_*`.
- `TOOL_START → TOOL_CALL_START`; `RESULT → TOOL_CALL_RESULT`;
  `TOOL_ERROR → TOOL_CALL_RESULT` (error payload).
- `PROGRESS/VERIFICATION` + `executionPaths` → `STATE_DELTA` (see §4); granular
  orchestration detail can also go as `CUSTOM`.
- `SUSPEND/FORM → CUSTOM` (HITL prompt); `RESUME/CANCEL → CUSTOM`/lifecycle.
- `TRACE/SPAN/METRIC/LOG/HEARTBEAT` → dropped (observability/keepalive, not AG-UI;
  AG-UI has its own keepalive).

The mapper is the **only** place coupled to AG-UI types — the domain stays clean.

## 4. Shared state = FlowState (D9)

On `RUN_STARTED`, emit a `STATE_SNAPSHOT` of the run's projected state
(`{ status, executionPaths: [] }`). As the orchestration materializes
`FlowState.executionPaths` (design-0005 step 4), emit `STATE_DELTA` (JSON-Patch,
RFC-6902) appending the new round/subtask paths. The frontend then renders the
dynamic tree from AG-UI shared state — the same data the `ExecutionDetailPage`
shows today, but live and standard.

## 5. Endpoint + wiring

```java
@RestController
@RequestMapping("/ag-ui")
class AgUiController {
    // deps: WorkflowDeserializer, FlowEngine, EventStreamRegistry, AgUiEventMapper,
    //       InMemoryWorkflowRuntimeStore (reused from the execute path)
    @PostMapping(value = "/workflows/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter run(@PathVariable String id, @RequestBody RunAgentInput input) { … }
}
```

Reuses `WorkflowDeserializer` (flow id = executionId), `flowEngine.execute`, the
`EventStreamRegistry` (now wired into the engine), and `createExecution`/
`completeExecution` for history parity.

## 6. Frontend POC (CopilotKit)

- Add `@copilotkit/react-core` + `@copilotkit/react-ui`; wrap with
  `CopilotKit` pointed at the AG-UI endpoint; drop a `CopilotSidebar` in the
  workflow editor.
- `useCopilotReadable`: expose the current flow (nodes/edges) and selected node.
- `useCopilotAction`: `runWorkflow` (→ `POST /ag-ui/workflows/{id}`), later
  `addNode`/`connect`/`explainError` (→ existing assist endpoints).
- Verify the dynamic tree renders from `STATE_DELTA` and step lifecycle from
  `STEP_*`.

## 7. Dependencies / unknowns

- **AG-UI Java SDK**: add the Maven coordinate (verify the exact
  groupId/artifactId and version on Maven Central before P0). If a suitable Java
  SDK isn't available/stable, the event JSON is small and well-specified — a thin
  hand-rolled serializer is an acceptable fallback for P0.
- Auth on the SSE stream (EventSource can't set headers) — reuse the existing
  stream's token-in-query approach (`event-stream.ts`).

## 8. Sequência sugerida (P0)

1. `AgUiEventMapper` + `DefaultAgUiEventMapper` + unit tests (ArchflowEvent →
   AG-UI), no transport.
2. `AgUiController` `POST /ag-ui/workflows/{id}` teeing the hub → SSE; reuse
   engine + registry; emit RUN_STARTED/FINISHED bracket.
3. `STATE_SNAPSHOT`/`STATE_DELTA` from `executionPaths`.
4. CopilotKit `CopilotSidebar` + `runWorkflow` action; smoke end-to-end.

## 9. Status

**PROPOSED.** Nothing implemented. P0 is small because the hub, engine wiring,
lifecycle listener and state materialization already exist (design-0005); this
adds a mapper + one endpoint + a POC sidebar.
