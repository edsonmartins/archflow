package br.com.archflow.api.usage;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;

import java.util.Objects;

/**
 * Toda travessia de fluxo tem um {@link ContadorDeUso}, e o que ele mediu vai para o
 * {@link RegistroDeUso} quando a travessia termina (RCHFLW-75).
 *
 * <p>Por que no motor, e não em cada porta de entrada: o fluxo é executado pelo editor (AG-UI),
 * pela lista ({@code POST /api/workflows/{id}/execute}), por uma retomada depois de aprovação, pelo
 * servidor MCP e pelo VendaX. Só o VendaX punha um contador no contexto; nos outros o nó de agente
 * recebia {@code uso = null} e o laço pulava a contagem. O motor é o ponto que todos atravessam — uma
 * porta nova não tem como esquecer.
 *
 * <p>Quem já pôs um contador (o VendaX, que relata o uso ao Core com ele) fica com o seu: o listener
 * só cria um quando não há. O registro é o total do contador no fim — inclusive de uma travessia que
 * falhou ou parou num portão de aprovação, porque o que foi gasto até ali foi gasto. A retomada traz
 * um contador novo (ele é {@code transient}, não vai para o checkpoint) e a soma da execução junta
 * os dois.
 */
public final class RegistroDeUsoListener implements FlowLifecycleListener {

    private final RegistroDeUso registro;

    public RegistroDeUsoListener(RegistroDeUso registro) {
        this.registro = Objects.requireNonNull(registro, "registro");
    }

    @Override
    public void onFlowStarted(Flow flow, ExecutionContext context, int stepCount) {
        if (context != null && ContadorDeUso.de(context).isEmpty()) {
            ContadorDeUso.injetar(context, new ContadorDeUso());
        }
    }

    @Override
    public void onFlowCompleted(Flow flow, ExecutionContext context, FlowResult result, long durationMs) {
        registrar(flow, context);
    }

    @Override
    public void onFlowFailed(Flow flow, ExecutionContext context, Throwable error, long durationMs) {
        registrar(flow, context);
    }

    private void registrar(Flow flow, ExecutionContext context) {
        if (context == null) {
            return;
        }
        ContadorDeUso.de(context).ifPresent(contador -> registro.registrar(context.getTenantId(),
                flow != null ? flow.getId() : null, LancamentoDeUso.ORIGEM_FLUXO, contador));
    }
}
