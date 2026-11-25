package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-14 (parte): um cancelamento pedido enquanto o último step roda.
 *
 * <p>{@code POST /api/executions/{id}/cancel} já respondia CANCELLED ao chamador, mas a
 * travessia só consultava o sinal antes do próximo step — sem próximo, terminava COMPLETED
 * e o estado terminal sobrescrevia o STOPPED. O chamador ouvia "cancelado" e o registro
 * dizia "concluído". Usa o motor real ({@link FlowEngineFactory}), não um executor isolado.
 */
@DisplayName("Cancelar durante o último passo termina CANCELLED")
class CancelDuringLastStepE2ETest {

    private static final String FLOW_ID = "cancel-last-step";

    @Test
    @DisplayName("cancel enquanto o único step roda → resultado CANCELLED e estado STOPPED")
    void cancelWhileLastStepRunsEndsCancelled() throws Exception {
        FlowRepository flowRepository = new InMemoryFlowRepository();
        StateManager stateManager = new InMemoryStateManager();
        FlowEngine engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FlowStep lento = new FlowStep() {
            @Override public String getId() { return "lento"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(SimpleStepResult.ok("lento", "feito", 1));
            }
        };
        Flow flow = new SimpleFlow(FLOW_ID,
                new FlowMetadata("Lento", "", "1.0.0", null, null, List.of()), List.of(lento));
        flowRepository.save(flow);

        CompletableFuture<FlowResult> running = engine.execute(flow,
                new DefaultExecutionContext("acme", "u-1", FLOW_ID,
                        MessageWindowChatMemory.builder().maxMessages(5).build()));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        engine.cancel(FLOW_ID);
        release.countDown();
        FlowResult result = running.get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.STOPPED);
    }
}
