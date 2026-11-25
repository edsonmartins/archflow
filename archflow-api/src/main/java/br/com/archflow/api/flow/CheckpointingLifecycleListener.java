package br.com.archflow.api.flow;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStep;

import java.util.logging.Logger;

/**
 * Checkpoint durável por step (fecha o item 1.7 do plano de homologação):
 * após cada step concluído/falho, sincroniza as variáveis do contexto
 * (outputs, {@code __archflow.completedSteps}) para o {@link FlowState} e o
 * persiste. Com isso um crash no meio do fluxo deixa no banco o último
 * checkpoint — o resume incremental (1.9) retoma dos steps concluídos em vez
 * de reexecutar tudo.
 *
 * <p>Falha de persistência não derruba o fluxo: o executor engole exceções de
 * listener (safeLifecycle) e o estado terminal ainda é salvo pelo engine.
 */
public final class CheckpointingLifecycleListener implements FlowLifecycleListener {

    private static final Logger logger = Logger.getLogger(CheckpointingLifecycleListener.class.getName());

    private final StateManager stateManager;

    public CheckpointingLifecycleListener(StateManager stateManager) {
        this.stateManager = stateManager;
    }

    @Override
    public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        checkpoint(flow, context);
    }

    @Override
    public void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                             Throwable error, long durationMs) {
        checkpoint(flow, context);
    }

    @Override
    public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {
        checkpoint(flow, context);
    }

    /**
     * O portão de aprovação suspendeu: o estado já é AWAITING_APPROVAL (gravado por
     * {@code requestApproval}), e o checkpoint leva junto a conversa e as saídas até aqui —
     * o que o passo "concluído" levava antes de a suspensão ter callback próprio.
     */
    @Override
    public void onStepSuspended(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        checkpoint(flow, context);
    }

    /**
     * Ler o estado, copiar as variáveis e gravar é uma unidade sob o monitor do contexto — o mesmo
     * que {@code DefaultFlowEngine.submitApproval} segura ao gravar uma decisão enquanto a travessia
     * ainda está viva. Fora dele, uma decisão que chegasse entre a leitura e a gravação era apagada:
     * o checkpoint regravava o AWAITING_APPROVAL que tinha lido por cima do STOPPED (ou do pedido
     * já consumido), o pedido recusado voltava à fila decidível de novo e o estado durável perdia
     * quem decidiu. Com o lock, o checkpoint que vem depois da decisão lê o estado já decidido.
     */
    private void checkpoint(Flow flow, ExecutionContext context) {
        if (context == null) {
            return;
        }
        synchronized (context) {
            checkpointLocked(flow, context);
        }
    }

    private void checkpointLocked(Flow flow, ExecutionContext context) {
        try {
            FlowState state = context.getState();
            if (state == null) {
                return;
            }
            // A conversa vive no heap da ChatMemory; sem copiá-la para as
            // variáveis antes de persistir, o resume devolve o agente amnésico.
            FlowStateChatMemory.capture(context);
            // Sem o filtro, infraestrutura injetada no contexto (o host de MCP, por exemplo)
            // entra no estado durável e a serialização estoura — o fluxo roda e a retomada
            // some, com um WARN no lugar de um erro.
            state.setVariables(ExecutionKeys.persistiveis(context.getVariables()));
            stateManager.saveState(flow.getId(), state);
        } catch (Exception e) {
            logger.warning("Falha ao persistir checkpoint do fluxo " + flow.getId()
                    + ": " + e.getMessage());
        }
    }
}
