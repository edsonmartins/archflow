package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Um fluxo que pausa num nó APPROVAL não é um fluxo que "não devolveu JSON".
 *
 * <p>O motor devolve {@code PAUSED} sem saída. O {@code AgentFlowRunner} só olhava {@code FAILED},
 * então a pausa virava saída vazia e o Core recebia um {@code ERROR} enganoso. Apontado pelo lado
 * do VendaX em 17/09. Hoje nenhum fluxo do VendaX tem nó APPROVAL — o teste existe para que o
 * primeiro não produza um erro falso.</p>
 */
@DisplayName("fluxo pausado e cancelado")
class FluxoPausadoTest {

    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();

    private VendaxAgentDispatcher dispatcherCom(ExecutionStatus status, boolean gasta) {
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        FlowEngine engine = mock(FlowEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        when(engine.execute(any(), any())).thenAnswer(chamada -> {
            if (gasta) {
                ExecutionContext ctx = chamada.getArgument(1);
                ContadorDeUso.de(ctx).orElseThrow()
                        .somar("openrouter", "openrouter/m", 1000, 100);
            }
            return CompletableFuture.completedFuture(resultado(status));
        });
        AgentFlowRunner fluxo = new AgentFlowRunner(deserializer, provider,
                mock(FlowRepository.class), null);
        return new VendaxAgentDispatcher(mock(QpAgentService.class), mock(McpAgentRunner.class),
                mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class), null, fluxo);
    }

    private static VendaxInvoke invokeDeFluxo() {
        return new VendaxInvoke("1.0", "t1", "c1", "CS", "m1", "x", "LIGHT", "teste", "trace",
                null, "cli-1", "vend-1", "chave-CS", null, null,
                new DefinicaoDeAgente("FLUXO", Map.of("steps", List.of()), null, "sentiment@1",
                        List.of(), Map.of(), Map.of(), "cs@2"));
    }

    private static FlowResult resultado(ExecutionStatus status) {
        return new FlowResult() {
            @Override public Optional<Object> getOutput() { return Optional.empty(); }
            @Override public ExecutionStatus getStatus() { return status; }
            @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
            @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
        };
    }

    @Test
    @DisplayName("PAUSED: nenhum ERROR ao Core, e o gasto até ali vai como SUSPENSO")
    void pausado() {
        dispatcherCom(ExecutionStatus.PAUSED, true).runAndReport(invokeDeFluxo());

        assertThat(sender.sent)
                .as("antes: ERROR 'não devolveu um JSON' para um fluxo que só estava esperando")
                .isEmpty();
        assertThat(sender.relatos).singleElement().satisfies(r -> {
            assertThat(r.motivo()).isEqualTo(VendaxUsoRelato.Motivo.SUSPENSO);
            assertThat(r.uso().tokens()).isEqualTo(1100);
        });
    }

    @Test
    @DisplayName("PAUSED sem gasto: nada ao Core")
    void pausadoSemGasto() {
        dispatcherCom(ExecutionStatus.PAUSED, false).runAndReport(invokeDeFluxo());

        assertThat(sender.sent).isEmpty();
        assertThat(sender.relatos).isEmpty();
    }

    @Test
    @DisplayName("CANCELLED: ERROR com o motivo certo, e não 'não devolveu um JSON'")
    void cancelado() {
        dispatcherCom(ExecutionStatus.CANCELLED, true).runAndReport(invokeDeFluxo());

        assertThat(sender.sent).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("cancelado").doesNotContain("JSON");
            assertThat(r.uso().tokens()).as("cancelado também gastou").isEqualTo(1100);
        });
        assertThat(sender.relatos).isEmpty();
    }

    @Test
    @DisplayName("COMPLETED sem saída continua sendo o erro de sempre")
    void concluidoSemSaida() {
        dispatcherCom(ExecutionStatus.COMPLETED, false).runAndReport(invokeDeFluxo());

        assertThat(sender.sent).singleElement()
                .satisfies(r -> assertThat(r.error()).contains("JSON"));
    }
}
