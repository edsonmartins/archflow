package br.com.archflow.api.agui;

import br.com.archflow.agent.streaming.ArchflowEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static br.com.archflow.api.agui.AgUiEvent.fields;

/**
 * Translates archflow's native events to AG-UI events (ADR-0003 mapping table).
 *
 * <p>The {@code RUN_*} lifecycle bracket is owned by the {@code AgUiController}
 * (it knows {@code threadId}/{@code runId}), so {@code FLOW_STARTED/COMPLETED/FAILED}
 * map to nothing here. Pure observability/keepalive events are dropped.
 */
@Component
public class DefaultAgUiEventMapper implements AgUiEventMapper {

    @Override
    public List<AgUiEvent> toAgUi(ArchflowEvent event) {
        Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
        String id = event.getId();

        return switch (event.getType()) {
            // Run lifecycle is emitted by the controller (needs threadId/runId).
            case FLOW_STARTED, FLOW_COMPLETED, FLOW_FAILED -> List.of();

            case STEP_STARTED -> List.of(AgUiEvent.of("STEP_STARTED",
                    fields("stepName", str(data, "stepId", "step"))));
            case STEP_COMPLETED, STEP_FAILED, STEP_SKIPPED -> List.of(AgUiEvent.of("STEP_FINISHED",
                    fields("stepName", str(data, "stepId", "step"), "status", event.getType().name())));

            case MESSAGE -> {
                String content = str(data, "content", str(data, "message", str(data, "text", "")));
                yield List.of(
                        AgUiEvent.of("TEXT_MESSAGE_START", fields("messageId", id, "role", "assistant")),
                        AgUiEvent.of("TEXT_MESSAGE_CONTENT", fields("messageId", id, "delta", content)),
                        AgUiEvent.of("TEXT_MESSAGE_END", fields("messageId", id)));
            }
            case DELTA -> List.of(AgUiEvent.of("TEXT_MESSAGE_CHUNK",
                    fields("messageId", id, "role", "assistant",
                            "delta", str(data, "content", str(data, "delta", "")))));

            case THINKING, REFLECTION -> List.of(AgUiEvent.of("CUSTOM",
                    fields("name", "reasoning", "value", data)));

            case TOOL_START -> List.of(AgUiEvent.of("TOOL_CALL_START",
                    fields("toolCallId", id, "toolCallName", str(data, "tool", str(data, "name", "tool")))));
            case RESULT, TOOL_ERROR -> List.of(AgUiEvent.of("TOOL_CALL_RESULT",
                    fields("toolCallId", id, "content", data)));

            // Dynamic-orchestration progress / verdicts — CUSTOM for P0
            // (STATE_DELTA of executionPaths is design-0006 step 3 / P1).
            case PROGRESS, VERIFICATION -> List.of(AgUiEvent.of("CUSTOM",
                    fields("name", "orchestration." + event.getType().name().toLowerCase(), "value", data)));

            // Observability / keepalive — not part of the agent↔UI conversation.
            case TRACE, SPAN, METRIC, LOG, HEARTBEAT, CONNECTED, DISCONNECTED -> List.of();

            // Everything else (FORM/SUSPEND/RESUME/CANCEL/PAYLOAD_*/...) passes through as CUSTOM.
            default -> List.of(AgUiEvent.of("CUSTOM",
                    fields("name", event.getType().name().toLowerCase(), "value", data)));
        };
    }

    private static String str(Map<String, Object> data, String key, String fallback) {
        Object v = data.get(key);
        return v == null ? fallback : v.toString();
    }
}
