# Design 0005 — Async flow execution (engine wiring, live streaming, resume)

Graduates flow execution from the synchronous linear runner (design-0004 step 1)
to the real `FlowEngine`, running asynchronously. This is the foundation that
unlocks the deferred halves of ADR-0002 D7 / design-0004 step 3: **live progress
streaming** and **dynamic FlowState materialization**.

## 1. Por que isto existe

design-0004 step 1 made stored workflows run, but via a `WorkflowRunner` that
executes steps **synchronously in the request thread** and returns only when
done. That blocks the caller and makes "live" anything impossible — there is no
run to observe while the HTTP call is still waiting. The real engine already
exists and is async/resumable; it just isn't wired or invoked.

What's already there:
- `DefaultFlowEngine` (virtual threads + `Semaphore` backpressure + per-flow
  timeout) → `DefaultExecutionManager` → `DefaultFlowExecutor` (traverses steps /
  connections, calls `FlowStep.execute`). Lifecycle: `execute`, `resumeFlow`,
  `pause`, `cancel`, `requestApproval`.
- Our executable steps from design-0004 (`ComponentStep`, `OrchestrateStep`) are
  plain `FlowStep`s — the real engine runs them unchanged.
- Streaming infra: `StreamController` (per-tenant SSE at
  `/archflow/stream/{tenantId}/{sessionId}`), the `ArchflowEvent` envelope, and
  the `FlowLifecycleListener` seam + `FlowLifecycleListeners.register(...)`
  registry (already used by `LinktorFlowPublisher`). The UI's
  `ArchflowEventStream`/`LiveEventsPage` consume this stream.
- `OrchestrationListener` (design-0004 step 3) for sub-step (subtask/verdict)
  granularity inside `OrchestrateStep`.

What's missing (gaps to fill):
- **No `StateManager` impl** (interface only) → needed for run state + resume.
- **No `MemoryRestorer` impl** (interface only).
- **No `FlowEngine` bean** wired in archflow-api.
- **No bridge** from flow/step lifecycle to the SSE stream.

## 2. Stand up the engine bean graph (archflow-api)

Add beans (reusing existing impls; new in-memory impls for the two gaps):

| Collaborator | Bean |
|---|---|
| `ExecutionManager` | `DefaultExecutionManager` (archflow-agent) |
| `FlowExecutor` | `DefaultFlowExecutor` (archflow-agent) |
| `FlowRepository` | `InMemoryFlowRepository` (already a bean) |
| `FlowValidator` | `DefaultFlowValidator` (archflow-core) |
| `TraceRecorder` | `TraceStoreRecorder` (archflow-api) |
| `FlowLifecycleListener` | `CompositeFlowLifecycleListener` over `FlowLifecycleListeners` registry |
| **`StateManager`** | **NEW `InMemoryStateManager`** (dev) — JDBC later |
| **`MemoryRestorer`** | **NEW no-op/in-memory `MemoryRestorer`** |
| `FlowEngine` | **NEW `@Bean DefaultFlowEngine(...)`** wired with the above |

The two new in-memory impls are small (a `ConcurrentHashMap<String, FlowState>`
for state; a no-op restorer) and keep dev/demo working without a database; the
JDBC `StateManager` is the production swap (behind `@ConditionalOnMissingBean`).

## 3. Async `/{id}/execute`

Replace the synchronous `WorkflowRunner` call with the engine:

```java
var execution = store.createExecution(id, name);   // status RUNNING
String executionId = (String) execution.get("id");

Flow flow = deserializer.toFlow(workflow);
ExecutionContext ctx = contextFor(executionId, tenantId, input);

flowEngine.execute(flow, ctx)                       // CompletableFuture, virtual thread
    .whenComplete((result, err) ->
        store.updateExecution(executionId, statusOf(result, err)));

return Map.of("executionId", executionId, "status", "RUNNING", ...); // returns immediately
```

The request returns at once; the run proceeds on the engine's virtual-thread
pool under its `Semaphore` backpressure and per-flow timeout. The execution-store
status (consumed by `ExecutionHistoryPage`) is updated on completion. The
synchronous `WorkflowRunner` is retired (or kept as a "run inline" debug option).

## 4. Live streaming (the deferred half of step 3)

A `FlowStreamPublisher implements FlowLifecycleListener`, registered via
`FlowLifecycleListeners.register(...)` (same pattern as `LinktorFlowPublisher`),
maps lifecycle callbacks to `ArchflowEvent`s and pushes them to the per-tenant
SSE stream:

- `onFlowStarted` → `flow.started`; `onStepStarted/onStepCompleted` →
  `step.started/step.completed`; `onFlowCompleted` → `flow.completed`.

For the dynamic tree inside an `OrchestrateStep`, bridge the
`OrchestrationListener` (step 3) to the same stream: `onPlanned`/`onVerified`/
`onRoundCompleted`/`onConverged` → `orchestration.*` events. `LiveEventsPage`
shows the run — and the dynamic subtasks — growing in real time.

## 5. Dynamic FlowState materialization

With async execution + a real `StateManager`, `OrchestrateStep` appends one
`ExecutionPath` per subtask to `ctx.getState()` (`FlowState.executionPaths`,
which already models `parallelBranches`/`PathStatus`) and persists via
`StateManager`. Consequences:

- The growing tree is **persisted** → resumable (`FlowEngine.resumeFlow` skips
  completed paths; the plan/seed are stored for replay).
- It is **inspectable** in observability and can be read back by the canvas to
  render the expanded tree under the `ORCHESTRATE` node.

## 6. Status, resume, cancel endpoints

- `GET /api/workflows/{id}/executions/{execId}` → status + step/path snapshot
  (from the execution store / `StateManager`).
- `POST .../{execId}/resume` → `flowEngine.resumeFlow`; `.../cancel` →
  `flowEngine.cancel`. The engine already implements these.

## 7. What stays out (harness / product)

Production `StateManager` (JDBC/Redis), auth on the SSE stream, and per-tenant
budget/SLA values remain product concerns; the engine wiring, async submission,
streaming bridge and materialization are the substrate.

## 8. Sequência sugerida

1. `InMemoryStateManager` + `MemoryRestorer` + `@Bean FlowEngine` (engine boots).
2. Async `/{id}/execute` via the engine; update execution status on completion;
   retire the synchronous runner. (Unblocks real, non-blocking execution.)
3. `FlowStreamPublisher` (lifecycle → SSE) + bridge `OrchestrationListener` →
   live `orchestration.*` events.
4. FlowState ExecutionPath materialization in `OrchestrateStep` + canvas readback.
5. Status/resume/cancel endpoints.

## 9. Status

**PROPOSED.** Nothing implemented. Step 1 (engine bean graph, incl. the two
in-memory gap impls) is the prerequisite and is worth landing on its own — it
turns the existing-but-dormant `FlowEngine` into a usable async executor for
every workflow, not just orchestration.
