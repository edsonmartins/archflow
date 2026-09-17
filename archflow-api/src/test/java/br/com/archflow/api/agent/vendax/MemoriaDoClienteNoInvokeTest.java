package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A memória do cliente que o Core manda no invoke chega ao laço, por todos os caminhos.
 *
 * <p>A memória é do Core (RFC-014 do VendaX): ele busca no Brain Sentry e escolhe os fatos. O que
 * este runtime garante é que eles cheguem ao modelo cercados e fora do prefixo cacheado — e que
 * nenhum caminho (QP, CS, NS, fluxo) os perca pelo caminho.</p>
 */
@DisplayName("memória do cliente no invoke")
class MemoriaDoClienteNoInvokeTest {

    private static final List<String> MEMORIA = List.of(
            "recusou Muçarela Fatiada 500g: não trabalha com a marca",
            "prefere caixa fechada");

    private final QpAgentService qp = mock(QpAgentService.class);
    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(qp, runner,
            mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class));

    private static VendaxInvoke invoke(String agente, String reason, List<String> memoria) {
        return new VendaxInvoke("1.0", "t1", "c1", agente, "m1", "quero 2 caixas", "LIGHT",
                reason, "trace", null, "cli-7", "vend-1", "chave", null, memoria, null);
    }

    private McpAgentRunner.Options opcoesDoRunner() {
        ArgumentCaptor<McpAgentRunner.Options> captor =
                ArgumentCaptor.forClass(McpAgentRunner.Options.class);
        verify(runner).run(anyString(), anyString(), anyString(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("CS: a memória vai nas opções do laço, com o contador de uso")
    void cs() {
        when(runner.run(anyString(), anyString(), anyString(), any(), any(McpAgentRunner.Options.class)))
                .thenReturn(new McpAgentRunner.Result("{\"score\":0}", List.of()));

        dispatcher.runAndReport(invoke("CS", "teste", MEMORIA));

        McpAgentRunner.Options o = opcoesDoRunner();
        assertThat(o.contextoRecuperado()).isEqualTo(MEMORIA);
        assertThat(o.uso()).isNotNull();
    }

    @Test
    @DisplayName("NS: idem, sem perder os códigos terminais")
    void ns() {
        when(runner.run(anyString(), anyString(), anyString(), any(), any(McpAgentRunner.Options.class)))
                .thenReturn(new McpAgentRunner.Result("{\"parametro\":{},\"resposta\":\"\"}", List.of()));

        dispatcher.runAndReport(invoke("NS", VendaxAgentDispatcher.NS_CONSULTA, MEMORIA));

        McpAgentRunner.Options o = opcoesDoRunner();
        assertThat(o.contextoRecuperado()).isEqualTo(MEMORIA);
        assertThat(o.codigosQueEncerram()).containsExactly(VendaxAgentDispatcher.NS_NAO_CONTRATADO);
        assertThat(o.tier()).isEqualTo("LIGHT");
    }

    @Test
    @DisplayName("QP: o ajuste das opções carrega a memória e o contador")
    @SuppressWarnings("unchecked")
    void qp() {
        when(qp.quote(any(), nullable(String.class), any()))
                .thenReturn(new QpAgentService.QpResult("sem cotação", List.of(), null, "qp-1"));

        dispatcher.runAndReport(invoke("QP", "teste", MEMORIA));

        ArgumentCaptor<UnaryOperator<McpAgentRunner.Options>> ajuste =
                ArgumentCaptor.forClass(UnaryOperator.class);
        verify(qp).quote(any(), nullable(String.class), ajuste.capture());
        McpAgentRunner.Options o = ajuste.getValue()
                .apply(new McpAgentRunner.Options(ToolAccessPolicy.allowAll()));
        assertThat(o.contextoRecuperado()).isEqualTo(MEMORIA);
        assertThat(o.uso()).isNotNull();
    }

    /** A memória é cercada pelo laço; no texto da entrada ela viraria fala do usuário. */
    @Test
    @DisplayName("a memória não entra no texto da entrada do agente")
    void naoVaiNaEntrada() {
        assertThat(dispatcher.entradaDoAgente(invoke("CS", "teste", MEMORIA)))
                .doesNotContain("caixa fechada")
                .doesNotContain("Muçarela");
    }

    @Test
    @DisplayName("sem memória, as opções vêm vazias — o agente roda como hoje")
    void semMemoria() {
        when(runner.run(anyString(), anyString(), anyString(), any(), any(McpAgentRunner.Options.class)))
                .thenReturn(new McpAgentRunner.Result("{\"score\":0}", List.of()));

        dispatcher.runAndReport(invoke("CS", "teste", null));

        assertThat(opcoesDoRunner().contextoRecuperado()).isEmpty();
    }

    @Test
    @DisplayName("fluxo: a memória vai no contexto, sob chave que não é persistida")
    void fluxo() {
        ExecutionContext contexto = executarFluxo(invoke("CS", "teste", MEMORIA));

        assertThat(McpAgentComponent.CTX_MEMORIA).startsWith(ExecutionKeys.TRANSIENT_PREFIX);
        assertThat(contexto.get(McpAgentComponent.CTX_MEMORIA)).contains(MEMORIA);
        assertThat(ExecutionKeys.persistiveis(contexto.getVariables()))
                .as("fatos sobre o cliente não vão para o estado durável do fluxo")
                .doesNotContainKey(McpAgentComponent.CTX_MEMORIA);
    }

    @Test
    @DisplayName("fluxo sem memória: nenhuma chave")
    void fluxoSemMemoria() {
        assertThat(executarFluxo(invoke("CS", "teste", List.of()))
                .get(McpAgentComponent.CTX_MEMORIA)).isEmpty();
    }

    @Test
    @DisplayName("o envelope desserializa a memória; ausente ou com nulos, vira lista limpa")
    void envelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        VendaxInvoke com = mapper.readValue("""
                {"tenantId":"t1","conversationId":"c1","agent":"CS",
                 "memoria":["prefere caixa fechada",null]}""", VendaxInvoke.class);
        VendaxInvoke sem = mapper.readValue("""
                {"tenantId":"t1","conversationId":"c1","agent":"CS"}""", VendaxInvoke.class);

        assertThat(com.memoria()).containsExactly("prefere caixa fechada");
        assertThat(sem.memoria()).isEmpty();
    }

    private ExecutionContext executarFluxo(VendaxInvoke invoke) {
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        FlowEngine engine = mock(FlowEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        when(engine.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(new Concluido()));

        new AgentFlowRunner(deserializer, provider, mock(FlowRepository.class), null)
                .executar(invoke, Map.of("steps", List.of()), "entrada");

        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(engine).execute(any(), ctx.capture());
        return ctx.getValue();
    }

    private static class Concluido implements FlowResult {
        @Override public Optional<Object> getOutput() { return Optional.of("{}"); }
        @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
        @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
        @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
    }
}
