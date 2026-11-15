package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NS e US viraram fluxo (VEN-1134): o {@code case} de cada um saiu do dispatcher.
 *
 * <p>O par: <b>sem</b> {@code definicao} de fluxo, o invoke cai no {@code naoImplementado} — erro
 * visível no Core, e não o prompt antigo rodando em silêncio; <b>com</b> ela, segue pelo caminho
 * genérico, como já seguia.</p>
 */
@DisplayName("NS e US sem case: só por fluxo")
class RetiradaDoNsEUsTest {

    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();

    private VendaxAgentDispatcher dispatcher() {
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        FlowEngine engine = mock(FlowEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        FlowResult resultado = new FlowResult() {
            @Override public Optional<Object> getOutput() {
                return Optional.of(Map.of("text", "{\"resposta\": \"ok\"}"));
            }
            @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
            @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
            @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
        };
        when(engine.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(resultado));
        AgentFlowRunner fluxo = new AgentFlowRunner(deserializer, provider, mock(FlowRepository.class), null);
        return new VendaxAgentDispatcher(mock(QpAgentService.class), runner,
                mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class), null, fluxo);
    }

    private static VendaxInvoke invoke(String agente, String reason, DefinicaoDeAgente definicao) {
        return new VendaxInvoke("1.0", "t1", "c1", agente, "m1", "posso dar 8%?", "LIGHT", reason, "trace",
                null, "cli-1", "vend-1", "chave-" + agente, null, null, definicao);
    }

    private static DefinicaoDeAgente fluxo(String versao) {
        return new DefinicaoDeAgente("FLUXO", Map.of("steps", List.of()), null, versao,
                List.of(), Map.of(), Map.of(), versao);
    }

    @ParameterizedTest(name = "{0} {1} sem definição → naoImplementado")
    @CsvSource({"NS, ns:consulta", "US, us:sugestao"})
    @DisplayName("sem definição de fluxo: erro visível, e o laço antigo não roda")
    void semDefinicao(String agente, String reason) {
        dispatcher().runAndReport(invoke(agente, reason, null));

        assertThat(sender.sent).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).isEqualTo("Agente " + agente + " ainda não é executado pelo ArchFlow");
        });
        verifyNoInteractions(runner);
    }

    @ParameterizedTest(name = "{0} {1} com definição → fluxo")
    @CsvSource({"NS, ns:consulta", "US, us:sugestao"})
    @DisplayName("com definição de fluxo: segue pelo caminho genérico")
    void comDefinicao(String agente, String reason) {
        dispatcher().runAndReport(invoke(agente, reason, fluxo(agente.toLowerCase() + "@6")));

        assertThat(sender.sent).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(r.richObject()).contains("\"resposta\"");
        });
        verifyNoInteractions(runner);
    }
}
