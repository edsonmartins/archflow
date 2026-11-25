package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.StateUpdate;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uma decisão que chega enquanto o checkpoint do portão suspenso está gravando não é apagada por ele.
 *
 * <p>O {@code requestApproval} grava AWAITING_APPROVAL e o pedido já está na fila; depois o
 * executor chama {@code onStepSuspended} e o {@link CheckpointingLifecycleListener} lê o estado do
 * contexto, copia as variáveis e grava. Um aprovador automático que recusava entre a leitura e a
 * gravação tinha o STOPPED sobrescrito pelo AWAITING_APPROVAL lido antes: o pedido voltava à fila,
 * decidível de novo, e o estado durável perdia quem o recusou — enquanto o registro dizia STOPPED.</p>
 *
 * <p>A janela é aberta de propósito: o store segura a gravação do checkpoint até a decisão ter
 * começado. Com o lock, a decisão espera o checkpoint (a thread dela fica BLOCKED) e grava por
 * último; sem ele, a decisão termina dentro da janela e o checkpoint a apaga.</p>
 */
@DisplayName("Checkpoint do portão suspenso não apaga uma decisão concorrente")
class ApprovalCheckpointRaceTest {

    private static final String FLOW_ID = "exec-corrida";

    /**
     * StateManager em memória que prende a gravação do checkpoint do portão (a segunda gravação
     * AWAITING_APPROVAL, vinda do {@link CheckpointingLifecycleListener}) até {@link #release}.
     */
    private static final class HoldingCheckpointStateManager implements StateManager {
        private final InMemoryStateManager delegate = new InMemoryStateManager();
        final CountDownLatch inCheckpointSave = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean armed = true;

        @Override
        public void saveState(String flowId, FlowState state) {
            if (armed && state.getStatus() == FlowStatus.AWAITING_APPROVAL && fromCheckpoint()) {
                armed = false;
                inCheckpointSave.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            delegate.saveState(flowId, state);
        }

        private static boolean fromCheckpoint() {
            return StackWalker.getInstance().walk(frames -> frames.anyMatch(
                    f -> f.getClassName().equals(CheckpointingLifecycleListener.class.getName())));
        }

        @Override
        public FlowState loadState(String flowId) {
            return delegate.loadState(flowId);
        }

        @Override
        public void updateState(String flowId, StateUpdate update) {
            delegate.updateState(flowId, update);
        }

        @Override
        public List<FlowState> findByStatus(FlowStatus status) {
            return delegate.findByStatus(status);
        }
    }

    @Test
    @DisplayName("recusa durante o checkpoint: o estado termina STOPPED, com o decisor, e o pedido não volta à fila")
    void rejectionDuringTheGateCheckpointSurvives() throws Exception {
        HoldingCheckpointStateManager stateManager = new HoldingCheckpointStateManager();
        FlowRepository flowRepository = new InMemoryFlowRepository();
        FlowEngine engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        ApprovalQueueService queue = new ApprovalQueueService(stateManager, engine);

        // O portão é o último passo — o caso de RCHFLW-14 —, com a travessia viva durante o checkpoint.
        HumanApprovalStep gate = new HumanApprovalStep("gate", List.of(),
                Map.of("description", "Reiniciar o serviço traefik"), () -> engine);
        Flow flow = new SimpleFlow(FLOW_ID, new FlowMetadata("Remediação", "", "1.0.0", null, null, List.of()),
                List.of(gate));
        flowRepository.save(flow);

        CompletableFuture<FlowResult> run = engine.execute(flow, new DefaultExecutionContext("acme", "sre-1",
                FLOW_ID, MessageWindowChatMemory.builder().maxMessages(5).build()));

        // O checkpoint já leu o estado AWAITING_APPROVAL e está gravando; o pedido está na fila.
        assertThat(stateManager.inCheckpointSave.await(10, TimeUnit.SECONDS)).isTrue();
        String requestId = queue.listPending("all").stream()
                .map(ApprovalResponse::requestId).findFirst().orElseThrow();

        AtomicReference<Throwable> decisionError = new AtomicReference<>();
        Thread decider = new Thread(() -> {
            try {
                queue.submitDecision(requestId, new ApprovalSubmitRequest("all", "REJECTED", null,
                        "aprovador.automatico", "risco acima do limite"));
            } catch (Throwable t) {
                decisionError.set(t);
            }
        }, "aprovador-automatico");
        decider.start();
        // A decisão começou: ou espera o checkpoint (com o lock) ou terminou dentro da janela (sem).
        long deadline = System.currentTimeMillis() + 10_000;
        while (decider.getState() != Thread.State.BLOCKED && decider.getState() != Thread.State.TERMINATED
                && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        stateManager.release.countDown();
        decider.join(10_000);
        run.get(10, TimeUnit.SECONDS);

        assertThat(decisionError.get()).isNull();
        FlowState durable = stateManager.loadState(FLOW_ID);
        assertThat(durable.getStatus()).as("status durável depois da recusa").isEqualTo(FlowStatus.STOPPED);
        assertThat(durable.getVariables().get(ExecutionKeys.APPROVAL_REQUEST_ID)).isEqualTo("");
        assertThat(durable.getVariables().get(ExecutionKeys.APPROVAL_DECISION)).isEqualTo("REJECTED");
        assertThat(durable.getVariables().get(ExecutionKeys.APPROVAL_DECIDED_BY)).isEqualTo("aprovador.automatico");
        assertThat((Collection<?>) durable.getVariables().get(ExecutionKeys.APPROVAL_HISTORY)).hasSize(1);
        assertThat(queue.listPending("all")).as("o pedido recusado não volta à fila").isEmpty();
    }
}
