package br.com.archflow.api.flow;

import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;

/**
 * Apaga o laço de agente que um nó {@code mcp-agent} segurava quando a travessia termina sem que o
 * portão continue aberto (RCHFLW-185).
 *
 * <p>O {@link McpAgentStep} guarda o laço suspenso marcado como do fluxo e abre o portão do motor. Os
 * desfechos que passam pela fila (aprovar, recusar, expirar) já cuidam do laço; o que sobra é a
 * execução que termina por outro caminho com {@link McpAgentStep#LACO_PENDENTE} ainda marcado — um
 * cancelamento que chega enquanto o passo do agente roda, ou um passo que falha com o laço marcado. O
 * estado do motor fica STOPPED/FAILED, nenhuma fila mostra o pedido, nada retoma o laço, e o store
 * guardaria para sempre o transcript e os argumentos da tool.</p>
 *
 * <p>Descarta só quando a travessia terminou sem parar no portão (resultado diferente de PAUSED) e o
 * estado final não é {@code AWAITING_APPROVAL}: a fila não decide portão fora desse estado, e o
 * {@code /resume} não reabre um. As duas condições, e não só o estado: quando a decisão chega com a
 * travessia original ainda desenrolando, o motor entrega <b>o mesmo contexto</b> à retomada, que o
 * põe em RUNNING antes de o passo consumir o laço — a travessia velha termina PAUSED e não pode
 * apagar o laço que a nova está para retomar.</p>
 *
 * <p>{@code onFlowFailed} fica de fora pelo mesmo motivo: ele não traz o resultado, e com o contexto
 * compartilhado o estado sozinho não distingue "falhou" de "foi retomado".</p>
 */
public final class LacoDoFluxoListener implements FlowLifecycleListener {

    @Override
    public void onFlowCompleted(Flow flow, ExecutionContext context, FlowResult result, long durationMs) {
        FlowState state = context != null ? context.getState() : null;
        if (state == null || result == null || result.getStatus() == ExecutionStatus.PAUSED
                || state.getStatus() == FlowStatus.AWAITING_APPROVAL) {
            return;
        }
        String laco = context.get(McpAgentStep.LACO_PENDENTE).map(String::valueOf).orElse(null);
        McpAgentStep.descartarLaco(context, laco, state.getFlowId());
    }
}
