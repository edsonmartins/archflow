# Design 0004 — Workflow execution wiring + orchestration nodes (D6 foundation)

Realizes **ADR-0002 D6** (orchestration nodes in the `Flow`). It first closes a
pre-existing foundation gap — there is no path today from a stored workflow JSON
to a running `Flow` — and then layers the dynamic-orchestration node types on top.

## 1. Por que isto existe

To run the dynamic-workflow primitives (design-0003) from the visual designer,
the engine must actually execute a flow authored as JSON. Today it does not:

- **No JSON → Flow deserializer.** The only `FlowStep` impl is
  `SerializableStep` (archflow-standalone), whose `execute()` throws
  *"Use StandaloneRunner to execute steps"*. Nothing in the API/Spring path turns
  the stored workflow document into executable `FlowStep`s.
- **`/api/workflows/{id}/execute` is a stub.** `SpringWorkflowCrudController`
  only creates an execution record (returns `executionId`/`status`); it never
  invokes the `FlowEngine`.
- **Good news:** the engine itself is ready. `DefaultFlowEngine` →
  `DefaultExecutionManager.executeFlow` → `DefaultFlowExecutor` traverses steps
  and calls `FlowStep.execute(ctx)`. Execution is **polymorphic on the step
  instance** — so we don't need to change the engine, only provide executable
  `FlowStep` implementations and a deserializer that builds them.

## 2. The seam: a `FlowStepFactory` keyed by `StepType`

The deserializer maps each node in the workflow JSON to a concrete, executable
`FlowStep`. A Spring-managed factory holds the collaborators (catalog, router,
LLM resolver, scorer, orchestrator) and builds steps from node config.

```java
package br.com.archflow.api.flow;

/** Builds an executable FlowStep from a workflow-JSON node (by StepType). */
public interface FlowStepFactory {
    FlowStep create(StepNode node);   // node = { id, type, componentId?, operation?, config, connections }
}
```

```java
package br.com.archflow.api.flow;

/** Parses a stored workflow JSON document into an executable Flow. */
public interface WorkflowDeserializer {
    Flow toFlow(Map<String, Object> workflowJson);
}
```

`DefaultFlowStepFactory` dispatch (one executable `FlowStep` impl per kind):

| StepType        | FlowStep impl        | execute(ctx) does |
|-----------------|----------------------|-------------------|
| ASSISTANT / AGENT / TOOL | `ComponentStep` | resolve `componentId` from `ComponentCatalog` (or route by `config.query` via `ComponentQueryRouter`), invoke the component/agent, wrap as `StepResult` |
| CHAIN           | `ChainStep`          | run a referenced sub-flow |
| CUSTOM          | (plugin-provided)    | as today |
| **ORCHESTRATE** | `OrchestrateStep`    | run `DynamicSupervisor` (plan→fan-out→verify→loop) from `config`; **materialize subtasks as ExecutionPaths** (§4) |
| **FAN_OUT**     | `FanOutStep`         | `Orchestrator.fanOut` over `config.items` (or a prior step's output) with a routed `CatalogAgentWorker` |
| **VERIFY**      | `VerifyStep`         | `Orchestrator.verify` over the incoming finding with a `ConfidenceVoter` (or LLM voter) |
| **LOOP_UNTIL**  | `LoopUntilStep`      | `Orchestrator.loopUntil` wrapping an inner step until convergence |

Because `DefaultFlowExecutor` already calls `step.execute(ctx)`, **no engine
change is required** — the orchestration steps reuse the substrate
(design-0003) and the wiring adapters (`CatalogAgentWorker`, `ConfidenceVoter`,
`LlmPlanner`, `DynamicSupervisor`) internally.

### Node config

Each node's `config` (already free-form JSON in the workflow document) carries
the policy from design-0003, e.g. for an `ORCHESTRATE` node:

```json
{ "id": "audit", "type": "ORCHESTRATE",
  "config": {
    "decomposePrompt": "Liste os arquivos a auditar",
    "worker": { "mode": "auto", "type": "AGENT" },
    "verify":  { "voters": 3, "minAgree": 2, "lenses": ["correctness","security"] },
    "converge":{ "maxRounds": 4, "dryRounds": 2 },
    "budgetTokens": 2000000 } }
```

## 3. Wiring `/{id}/execute` to the engine

Replace the stub: deserialize → submit to the engine, returning the execution id
the engine assigns. The run stays async/resumable (the engine already supports
`pause`/`resumeFlow`/`cancel`).

```java
Flow flow = workflowDeserializer.toFlow(store.getWorkflow(id));
CompletableFuture<FlowResult> run = flowEngine.execute(flow, contextFor(id, tenantId, input));
// return executionId immediately; progress via observability/LiveEvents (§5)
```

The per-run **token budget** (design-0003 D5) is read from flow/governance and
placed in the `ExecutionContext`; the orchestration steps enforce it.

## 4. Dynamic FlowState materialization

`OrchestrateStep` decides its subtasks at runtime, so the graph must grow during
the run. `FlowState` already models this: `executionPaths` with
`parallelBranches` and `PathStatus`. On planning, the step appends one
`ExecutionPath` per subtask (status `RUNNING` → `COMPLETED`/`FAILED`), persisted
via `StateManager`. Result:

- The dynamic tree is **persisted** → the run is resumable (re-plan is skipped
  for already-completed paths on resume; seed/plan are stored for replay).
- The tree is **inspectable** in observability (trace spans per subtask via
  `ArchflowTracer.startAgent`/`startTool`).

## 5. Live streaming

Each transition (plan produced, subtask dispatched, verdict, round, converge)
emits an event on the existing **LiveEvents SSE** channel that `LiveEventsPage`
already consumes — the "scalable visibility" for hours-long runs, no polling.
`OrchestrateStep` publishes via the same bus the realtime bridge uses.

## 6. Designer (FlowCanvas)

- Add the four node kinds to the palette with a distinct "orchestration"
  category; render `ORCHESTRATE` as a container whose children expand at run time
  (read back from `FlowState.executionPaths`).
- A `PropertyPanel` section maps 1:1 to the node `config` (§2): decompose prompt,
  worker mode (fixed agent vs. `auto` routing), verify policy, converge policy,
  budget. `worker.mode = auto` surfaces the `ComponentQueryRouter`
  (`/api/catalog/route`) so the user picks "let archflow choose the agent".
- The existing no-code runner page (`/playground/orchestration`) stays as the
  quick form-based entry; the canvas is for composing orchestration **within** a
  larger flow.

## 7. What stays out (harness / product)

The domain decomposition and verification logic (what to decompose, how to judge
a finding in a given business) live in the node `config` / product, per the
ADR-0001 boundary. The engine, factory, materialization, streaming and primitives
are the substrate.

## 8. Sequência sugerida

1. **Foundation (no nodes yet):** `WorkflowDeserializer` + `ComponentStep` +
   `DefaultFlowStepFactory` for ASSISTANT/AGENT/TOOL; wire `/{id}/execute` to the
   engine; smoke a trivial 2-step flow end-to-end. (Unblocks ALL flow execution,
   not just orchestration.)
2. **Orchestration nodes:** `OrchestrateStep`/`FanOutStep`/`VerifyStep`/
   `LoopUntilStep` + the `StepType` enum values + node config parsing. Reuse the
   design-0003 adapters.
3. **Materialization + streaming:** ExecutionPath append + LiveEvents events.
4. **Designer:** palette nodes + PropertyPanel config + container rendering.

## 9. Status

**PROPOSED.** Nothing implemented. Note the prerequisite in step 1 is a general
gap (no JSON→Flow execution exists in the API today) — worth landing on its own
merits, with the orchestration nodes (steps 2–4) layered after.
