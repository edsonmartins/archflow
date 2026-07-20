package br.com.archflow.api.agui;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static br.com.archflow.api.agui.AgUiEvent.fields;

/**
 * AG-UI-compliant endpoint (ADR-0003 / design-0006): runs a workflow and streams
 * its progress as AG-UI events in a single request, by teeing the existing
 * {@link EventStreamRegistry} through the {@link AgUiEventMapper}. Reuses the
 * engine, the registry (wired in design-0005 step 3) and the execution store —
 * no new streaming infra. The controller owns the RUN_* lifecycle bracket.
 */
@RestController
@RequestMapping("/ag-ui")
public class AgUiController {

    private final WorkflowRuntimeStore store;
    private final WorkflowDeserializer deserializer;
    private final FlowEngine flowEngine;
    private final EventStreamRegistry streamRegistry;
    private final AgUiEventMapper mapper;
    private final StateManager stateManager;
    private final ObjectMapper json;

    public AgUiController(WorkflowRuntimeStore store,
                          WorkflowDeserializer deserializer,
                          FlowEngine flowEngine,
                          EventStreamRegistry streamRegistry,
                          AgUiEventMapper mapper,
                          StateManager stateManager,
                          ObjectMapper jackson2ObjectMapper) {
        this.store = store;
        this.deserializer = deserializer;
        this.flowEngine = flowEngine;
        this.streamRegistry = streamRegistry;
        this.mapper = mapper;
        this.stateManager = stateManager;
        this.json = jackson2ObjectMapper;
    }

    @PostMapping(value = "/workflows/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String id, @RequestBody(required = false) RunAgentInput input) {
        SseEmitter sse = new SseEmitter(0L); // no server-side timeout; closed on run completion
        Object sendLock = new Object();      // engine may broadcast from parallel threads

        var workflow = store.getWorkflow(id);
        if (workflow == null) {
            emit(sse, sendLock, AgUiEvent.of("RUN_ERROR", fields("message", "workflow not found: " + id)));
            sse.complete();
            return sse;
        }

        var execution = store.createExecution(id, workflowName(workflow));
        String runId = String.valueOf(execution.get("id"));        // = flow id, as in /execute
        String threadId = input != null && input.threadId() != null ? input.threadId() : runId;

        Map<String, Object> flowJson = new HashMap<>(workflow);
        flowJson.put("id", runId);
        Flow flow = deserializer.toFlow(flowJson);

        ExecutionContext ctx = new DefaultExecutionContext(
                null, "ag-ui", runId, MessageWindowChatMemory.builder().maxMessages(20).build());
        seedInput(ctx, input);

        // Tee the hub into this response, filtered to this run. The lifecycle
        // listener broadcasts each event twice (flow-scoped + admin-scoped) and
        // both reach global listeners, so dedup by event id.
        java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Consumer<ArchflowEvent> listener = ev -> {
            if (runId.equals(ev.getExecutionId()) && seen.add(ev.getId())) {
                for (AgUiEvent agUi : mapper.toAgUi(ev)) {
                    emit(sse, sendLock, agUi);
                }
            }
        };
        streamRegistry.addGlobalListener(listener);
        sse.onError(t -> cleanup(listener, runId));
        sse.onTimeout(() -> cleanup(listener, runId));

        // Controller owns the RUN_* bracket (it knows threadId/runId).
        emit(sse, sendLock, AgUiEvent.of("RUN_STARTED", fields("threadId", threadId, "runId", runId)));
        emit(sse, sendLock, AgUiEvent.of("STATE_SNAPSHOT",
                fields("snapshot", Map.of("status", "RUNNING", "executionPaths", List.of()))));

        flowEngine.execute(flow, ctx).whenComplete((result, err) -> {
            synchronized (sendLock) {
                // Final shared-state snapshot: the materialized dynamic tree (D9).
                if (err == null) {
                    FlowState state = stateManager.loadState(runId);
                    if (state != null && state.getExecutionPaths() != null) {
                        writeQuietly(sse, AgUiEvent.of("STATE_SNAPSHOT", fields("snapshot",
                                Map.of("status", statusOf(result, null),
                                        "executionPaths", state.getExecutionPaths()))));
                    }
                }
                AgUiEvent terminal = err != null
                        ? AgUiEvent.of("RUN_ERROR", fields("runId", runId, "message", errorOf(err)))
                        : AgUiEvent.of("RUN_FINISHED", fields("threadId", threadId, "runId", runId,
                                "result", Map.of("status", statusOf(result, null))));
                writeQuietly(sse, terminal);
            }
            streamRegistry.removeGlobalListener(listener);
            store.completeExecution(runId, statusOf(result, err), errorOf(err));
            sse.complete();
        });

        return sse;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void emit(SseEmitter sse, Object lock, AgUiEvent event) {
        synchronized (lock) {
            writeQuietly(sse, event);
        }
    }

    private void writeQuietly(SseEmitter sse, AgUiEvent event) {
        try {
            sse.send(SseEmitter.event().data(json.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // client gone / stream closed — the whenComplete cleanup handles the rest
        }
    }

    private void cleanup(Consumer<ArchflowEvent> listener, String runId) {
        streamRegistry.removeGlobalListener(listener);
        try {
            flowEngine.cancel(runId);
        } catch (RuntimeException ignored) {
            // not active / already done
        }
    }

    private void seedInput(ExecutionContext ctx, RunAgentInput input) {
        if (input == null) {
            return;
        }
        if (input.state() != null) {
            input.state().forEach(ctx::set);
            ctx.set("input", input.state());
        }
        if (input.messages() != null && !input.messages().isEmpty()) {
            Object content = input.messages().get(input.messages().size() - 1).get("content");
            if (content != null) {
                ctx.set("input", content);
            }
        }
    }

    private static String workflowName(Map<String, Object> workflow) {
        Object meta = workflow.get("metadata");
        if (meta instanceof Map<?, ?> m && m.get("name") != null) {
            return m.get("name").toString();
        }
        return "Untitled";
    }

    private static String statusOf(FlowResult result, Throwable err) {
        if (err != null || result == null) {
            return "FAILED";
        }
        return result.getStatus() != null ? result.getStatus().name() : "COMPLETED";
    }

    private static String errorOf(Throwable err) {
        if (err == null) {
            return null;
        }
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
