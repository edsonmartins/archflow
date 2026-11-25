package br.com.archflow.api.agui;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgUiEventMapperTest {

    private final AgUiEventMapper mapper = new DefaultAgUiEventMapper();

    private static ArchflowEvent event(ArchflowDomain domain, ArchflowEventType type, Map<String, Object> data) {
        return ArchflowEvent.builder().domain(domain).type(type).executionId("exec-1").data(data).build();
    }

    @Test
    void mapsStepLifecycle() {
        List<AgUiEvent> started = mapper.toAgUi(event(ArchflowDomain.FLOW, ArchflowEventType.STEP_STARTED, Map.of("stepId", "s1")));
        assertThat(started).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STEP_STARTED");
            assertThat(e.getFields()).containsEntry("stepName", "s1");
        });

        List<AgUiEvent> finished = mapper.toAgUi(event(ArchflowDomain.FLOW, ArchflowEventType.STEP_COMPLETED, Map.of("stepId", "s1")));
        assertThat(finished).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STEP_FINISHED");
            assertThat(e.getFields()).containsEntry("status", "STEP_COMPLETED");
        });
    }

    /**
     * O portão de aprovação à espera fecha o STEP_STARTED (cliente AG-UI genérico não fica com um
     * passo aberto), mas com um status próprio — é ele que o editor lê como "aguardando aprovação"
     * em vez de ✓ (RCHFLW-32).
     */
    @Test
    void mapsSuspendedStepToFinishedWithItsOwnStatus() {
        List<AgUiEvent> suspended = mapper.toAgUi(event(ArchflowDomain.FLOW, ArchflowEventType.STEP_SUSPENDED,
                Map.of("stepId", "gate", "stepIndex", 1, "durationMs", 7L)));
        assertThat(suspended).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STEP_FINISHED");
            assertThat(e.getFields()).containsEntry("status", "STEP_SUSPENDED")
                    .containsEntry("stepId", "gate")
                    .containsEntry("stepIndex", 1);
        });
    }

    @Test
    void mapsMessageToTextBracket() {
        List<AgUiEvent> events = mapper.toAgUi(event(ArchflowDomain.CHAT, ArchflowEventType.MESSAGE, Map.of("content", "hello")));
        assertThat(events).extracting(AgUiEvent::getType)
                .containsExactly("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END");
        assertThat(events.get(1).getFields()).containsEntry("delta", "hello");
    }

    @Test
    void mapsDeltaToChunk() {
        List<AgUiEvent> events = mapper.toAgUi(event(ArchflowDomain.CHAT, ArchflowEventType.DELTA, Map.of("content", "tok")));
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("TEXT_MESSAGE_CHUNK");
            assertThat(e.getFields()).containsEntry("delta", "tok");
        });
    }

    @Test
    void mapsToolStartAndOrchestrationProgress() {
        assertThat(mapper.toAgUi(event(ArchflowDomain.TOOL, ArchflowEventType.TOOL_START, Map.of("tool", "search"))))
                .singleElement().satisfies(e -> {
                    assertThat(e.getType()).isEqualTo("TOOL_CALL_START");
                    assertThat(e.getFields()).containsEntry("toolCallName", "search");
                });

        assertThat(mapper.toAgUi(event(ArchflowDomain.FLOW, ArchflowEventType.PROGRESS, Map.of("round", 1))))
                .singleElement().satisfies(e -> {
                    assertThat(e.getType()).isEqualTo("CUSTOM");
                    assertThat(e.getFields()).containsEntry("name", "orchestration.progress");
                });
    }

    @Test
    void dropsRunLifecycleAndKeepalive() {
        // Controller owns RUN_*; keepalive/observability is not part of the AG-UI conversation.
        assertThat(mapper.toAgUi(event(ArchflowDomain.FLOW, ArchflowEventType.FLOW_STARTED, Map.of()))).isEmpty();
        assertThat(mapper.toAgUi(event(ArchflowDomain.SYSTEM, ArchflowEventType.HEARTBEAT, Map.of()))).isEmpty();
    }
}
