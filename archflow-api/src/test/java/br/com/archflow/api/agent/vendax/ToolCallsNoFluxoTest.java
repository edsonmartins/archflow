package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As chamadas de tool do fluxo chegam ao Core (pedido do VendaX de 22/09/2026).
 *
 * <p>É o que falta para um agente sair do {@code switch} por nome: o Core monta o parâmetro da
 * resposta a partir dos <b>argumentos</b> que foram à tool, não do que o modelo declara — "chama com
 * 850 e relata 800". O caminho por {@code case} fazia isso dentro do ArchFlow
 * ({@link ConsultaAoNegociador}); o caminho de fluxo descartava as chamadas na
 * {@link AgentFlowRunner.Saida}, antes do dispatcher.</p>
 */
@DisplayName("tool calls no caminho de fluxo")
class ToolCallsNoFluxoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Object> DOCUMENTO = Map.of(
            "id", "vendax-ns",
            "steps", List.of(Map.of("id", "consulta", "type", "mcp-agent")));

    private final VendaxResultSender sender = mock(VendaxResultSender.class);
    private final AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
            mock(QpAgentService.class), mock(McpAgentRunner.class),
            mock(VendaxMcpClientProvider.class), sender, Executors.newSingleThreadExecutor(),
            null, fluxo);

    private static VendaxInvoke invoke() {
        return new VendaxInvoke("1.0", "t1", "c1", "NS", "m1", "posso dar 8,5 no filtro?", "LIGHT",
                "ns:consulta", "trace-ns", null, "20572", "vend-7",
                new DefinicaoDeAgente("FLUXO", DOCUMENTO, null, "ns_consulta@1",
                        List.of(), Map.of(), Map.of(), "ns@1"));
    }

    private VendaxResult executar(AgentFlowRunner.Saida saida) {
        when(fluxo.executar(any(), eq(DOCUMENTO), any(), any())).thenReturn(saida);
        dispatcher.runAndReport(invoke());
        ArgumentCaptor<VendaxResult> captor = ArgumentCaptor.forClass(VendaxResult.class);
        verify(sender).send(captor.capture(), nullable(String.class));
        return captor.getValue();
    }

    /** Os argumentos como o modelo os mandou: é o dado que a conferência do Core compara. */
    @Test
    @DisplayName("o result leva as chamadas, na ordem, com os argumentos e o isError")
    void levaAsChamadas() {
        VendaxResult r = executar(new AgentFlowRunner.Saida("{\"frase\":\"pode ir a 8,5\"}", false,
                List.of(new VendaxResult.ToolCall("obter_cliente_360", Map.of("clienteRef", "20572"), false),
                        new VendaxResult.ToolCall("plano_negociacao",
                                Map.of("clienteRef", "20572",
                                        "itens", List.of(Map.of("skuRef", "77012:1",
                                                "descontoPedidoPb", 850))), false)),
                null));

        assertThat(r.status()).isEqualTo(VendaxResult.OK);
        assertThat(r.toolCalls()).extracting(VendaxResult.ToolCall::name)
                .containsExactly("obter_cliente_360", "plano_negociacao");
        VendaxResult.ToolCall plano = r.toolCalls().get(1);
        assertThat(plano.isError()).isFalse();
        assertThat(plano.arguments()).containsEntry("clienteRef", "20572");
        assertThat(((List<?>) plano.arguments().get("itens")).get(0))
                .isEqualTo(Map.of("skuRef", "77012:1", "descontoPedidoPb", 850));
    }

    /** "Não chamou nada" e "não informou" não podem se confundir do lado do Core. */
    @Test
    @DisplayName("fluxo sem tool nenhuma: lista vazia, não nula")
    void semChamadas() {
        VendaxResult r = executar(new AgentFlowRunner.Saida("{\"frase\":\"sem plano\"}", false));

        assertThat(r.toolCalls()).isNotNull().isEmpty();
        assertThat(r.encerradoPor()).isNull();
    }

    @Test
    @DisplayName("o que encerrou o laço vai junto: nome e código")
    void encerramento() {
        VendaxResult r = executar(new AgentFlowRunner.Saida("", false, List.of(
                new VendaxResult.ToolCall("plano_negociacao", Map.of(), true)),
                new VendaxResult.Encerramento("plano_negociacao", -32001)));

        // Encerrado, o laço não redige: o result é ERROR — e é justamente aí que o Core precisa
        // saber POR QUE, para não confundir com "o modelo não redigiu".
        assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
        assertThat(r.error()).contains("plano_negociacao").contains("-32001");
        assertThat(r.encerradoPor().name()).isEqualTo("plano_negociacao");
        assertThat(r.encerradoPor().code()).isEqualTo(-32001);
        assertThat(r.toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.isError()).isTrue());
    }

    @Nested
    @DisplayName("no envelope que vai ao Core")
    class NoEnvelope {

        @Test
        @DisplayName("toolCalls sai com name, arguments e isError — e sem o resultado da tool")
        void forma() throws Exception {
            VendaxResult r = executar(new AgentFlowRunner.Saida("{\"frase\":\"ok\"}", false,
                    List.of(new VendaxResult.ToolCall("plano_negociacao",
                            Map.of("descontoPedidoPb", 850), false)), null));

            var envelope = MAPPER.readTree(MAPPER.writeValueAsString(r));
            var chamada = envelope.path("toolCalls").get(0);
            assertThat(chamada.path("name").asText()).isEqualTo("plano_negociacao");
            assertThat(chamada.path("arguments").path("descontoPedidoPb").asInt()).isEqualTo(850);
            assertThat(chamada.path("isError").asBoolean()).isFalse();
            assertThat(chamada.has("result")).as("o Core é dono das tools; o payload pode ser grande")
                    .isFalse();
        }

        /** Campo aditivo: quem produziu o result sem chamadas não ganha um campo vazio no envelope. */
        @Test
        @DisplayName("result do caminho por nome sai sem o campo")
        void caminhoPorNome() throws Exception {
            var envelope = MAPPER.readTree(MAPPER.writeValueAsString(
                    VendaxResult.ok(invoke(), "sentiment", "{\"score\":-3}")));

            assertThat(envelope.has("toolCalls")).isFalse();
            assertThat(envelope.has("encerradoPor")).isFalse();
        }
    }

    /** A Saida traduz o mapa do nó; se a chave mudar de nome, é aqui que aparece. */
    @Nested
    @DisplayName("a saída do nó mcp-agent")
    class DoNo {

        @Test
        @DisplayName("as chaves que a Saida lê são as que o nó escreve")
        void chaves() {
            assertThat(McpAgentComponent.SAIDA_TOOLS).isEqualTo("toolCalls");
            assertThat(McpAgentComponent.SAIDA_ENCERROU).isEqualTo("encerradoPor");
        }
    }
}
