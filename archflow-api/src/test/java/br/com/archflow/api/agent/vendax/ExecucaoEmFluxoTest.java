package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.SaidaDeTexto;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.agent.stream.ExecucoesEmAndamento;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O que a execução em fluxo muda no dispatcher — e o que ela deixa como estava.
 */
@DisplayName("execução em fluxo no dispatcher")
class ExecucaoEmFluxoTest {

    private final VendaxResultSender sender = mock(VendaxResultSender.class);
    private final AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
    private final ExecucoesEmAndamento execucoes = new ExecucoesEmAndamento();
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
            mock(QpAgentService.class), mock(McpAgentRunner.class), mock(VendaxMcpClientProvider.class),
            sender, Executors.newSingleThreadExecutor(), null, fluxo);

    private static final Map<String, Object> DOCUMENTO = Map.of(
            "id", "demo", "steps", List.of(Map.of("id", "a", "type", "mcp-agent")));

    private static VendaxInvoke invoke(String chave, String saidaSchema) {
        return new VendaxInvoke("1.0", "t1", "c1", "assistente", "m1", "olá", "LIGHT", "teste",
                "trace", null, null, null, chave, null,
                new DefinicaoDeAgente("FLUXO", DOCUMENTO, null, saidaSchema,
                        List.of(), Map.of(), Map.of(), "demo@1"));
    }

    private static SaidaDeTexto saida(boolean transmite) {
        return transmite ? SaidaDeTexto.soCancelamento(new CompletableFuture<>()).semTexto()
                : SaidaDeTexto.soCancelamento(new CompletableFuture<>());
    }

    @Test
    @DisplayName("sem saidaSchema a saída é texto: richObjectType=text e o nó final é marcado para transmitir")
    void semSchemaEhTexto() {
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentFlowRunner.Saida("Boa tarde!", false));

        var feito = dispatcher.executarEmFluxo(invoke("k1", null), saida(true), new ContadorDeUso());

        assertThat(feito.resultado().status()).isEqualTo(VendaxResult.OK);
        assertThat(feito.resultado().richObjectType()).isEqualTo("text");
        assertThat(feito.resultado().richObject()).isEqualTo("Boa tarde!");
        verify(fluxo).executar(any(), eq(DOCUMENTO), any(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("com saidaSchema o resultado é o tipado de sempre e o texto não é transmitido")
    void comSchemaNaoTransmite() {
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentFlowRunner.Saida("{\"a\":1}", false));

        var feito = dispatcher.executarEmFluxo(invoke("k2", "demo@1"), saida(false), new ContadorDeUso());

        assertThat(feito.resultado().richObjectType()).isEqualTo("demo");
        verify(fluxo).executar(any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("fluxo suspenso em aprovação vira erro: não cabe numa resposta em fluxo")
    void suspensoEhErro() {
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentFlowRunner.Saida(null, true));

        var feito = dispatcher.executarEmFluxo(invoke("k3", null), saida(true), new ContadorDeUso());

        assertThat(feito.resultado().status()).isEqualTo(VendaxResult.ERROR);
        assertThat(feito.resultado().error()).contains("decisão humana");
    }

    @Test
    @DisplayName("exceção do fluxo é erro recuperável; o prazo do envelope já vencido, não")
    void recuperavel() {
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("provedor caiu"));
        assertThat(dispatcher.executarEmFluxo(invoke("k4", null), saida(true), new ContadorDeUso())
                .recuperavel()).isTrue();

        VendaxInvoke vencido = new VendaxInvoke("1.0", "t1", "c1", "assistente", "m1", "olá", "LIGHT",
                "teste", "trace", null, null, null, "k5", null, null, "2020-01-01T00:00:00Z",
                invoke("k5", null).definicao());
        var feito = dispatcher.executarEmFluxo(vencido, saida(true), new ContadorDeUso());
        assertThat(feito.resultado().status()).isEqualTo(VendaxResult.ERROR);
        assertThat(feito.recuperavel()).isFalse();
    }

    @Test
    @DisplayName("o primeiro token medido viaja no uso do resultado")
    void msAtePrimeiroTokenNoUso() {
        ContadorDeUso uso = new ContadorDeUso();
        uso.somar("p", "p/m", 10, 5);
        uso.registrarPrimeiroToken();
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentFlowRunner.Saida("oi", false));

        var feito = dispatcher.executarEmFluxo(invoke("k6", null), saida(true), uso);

        assertThat(feito.resultado().uso().msAtePrimeiroToken()).isNotNull();
    }

    @Test
    @DisplayName("execução assíncrona cancelada por chave: o webhook recebe CANCELADO, com a mesma chave")
    void cancelaAssincrona() throws Exception {
        dispatcher.setExecucoes(execucoes);
        CountDownLatch dentro = new CountDownLatch(1);
        when(fluxo.executar(any(), any(), any(), any(), any(), eq(false))).thenAnswer(inv -> {
            dentro.countDown();
            SaidaDeTexto cancel = inv.getArgument(4);
            try {
                cancel.cancelamento().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // interrompida pelo cancelamento
            }
            throw new IllegalStateException("Fluxo cancelado antes de concluir");
        });

        dispatcher.dispatch(invoke("k7", "demo@1"));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(execucoes.cancelar("k7")).isTrue();

        ArgumentCaptor<VendaxResult> enviado = ArgumentCaptor.forClass(VendaxResult.class);
        verify(sender, timeout(5000)).send(enviado.capture(), nullable(String.class));
        assertThat(enviado.getValue().encerradoPor().name()).isEqualTo("CANCELADO");
        assertThat(enviado.getValue().idempotencyKey()).isEqualTo("k7");
        assertThat(execucoes.emAndamento("k7")).isFalse();
    }

    @Test
    @DisplayName("só os agentes finais, sem aresta de saída, são marcados para transmitir")
    void marcaSoOsNosFinais() {
        Object marcados = AgentFlowRunner.marcandoOsNosFinais(List.of(
                Map.of("id", "a", "type", McpAgentComponent.COMPONENT_ID,
                        "connections", List.of(Map.of("targetId", "b"))),
                Map.of("id", "b", "type", McpAgentComponent.COMPONENT_ID, "config", Map.of("x", 1)),
                Map.of("id", "c", "type", "outro-componente")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> passos = (List<Map<String, Object>>) marcados;
        assertThat(passos.get(0)).as("tem aresta: é intermediário").doesNotContainKey("config");
        assertThat(passos.get(1).get("config")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry(McpAgentComponent.CFG_TRANSMITIR_TEXTO, true)
                .containsEntry("x", 1);
        assertThat(passos.get(2)).as("não é agente").doesNotContainKey("config");
    }
}
