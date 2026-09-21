package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O motivo da leitura do CS em vocabulário fechado (VendaX, ADR-039).
 *
 * <p>O que se protege: nada fora da tabela sai daqui, qualquer que seja o prompt que rodou; um motivo
 * ruim nunca custa a leitura; e ausente não vira {@code NENHUM} por conta nossa.</p>
 */
@DisplayName("CS — motivo da leitura")
class MotivoDaLeituraTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String leitura(String motivoJson) {
        return "{\"score\":-6,\"trend\":\"CAINDO\",\"motivo\":" + motivoJson
                + ",\"tone\":\"peça desculpas pelo corte\",\"bigCustomer\":true}";
    }

    private static JsonNode normalizada(String json) throws Exception {
        return MAPPER.readTree(MotivoDaLeitura.normalizar(json));
    }

    @Nested
    @DisplayName("a trava do vocabulário")
    class Trava {

        @ParameterizedTest
        @ValueSource(strings = {"CORTE_OU_FALTA", "ATRASO_NA_ENTREGA", "QUALIDADE_DO_PRODUTO", "PRECO",
                "FINANCEIRO", "ATENDIMENTO", "ANDAMENTO_DO_PEDIDO", "OUTRO", "NENHUM"})
        @DisplayName("valor da tabela passa como veio")
        void daTabela(String valor) throws Exception {
            assertThat(normalizada(leitura('"' + valor + '"')).path("motivo").asText()).isEqualTo(valor);
        }

        /** Caixa, acento e separador não mudam o que o modelo quis dizer. */
        @ParameterizedTest
        @CsvSource({"corte ou falta,CORTE_OU_FALTA", "Preço,PRECO", " atraso-na-entrega ,ATRASO_NA_ENTREGA",
                "nenhum,NENHUM"})
        @DisplayName("só a forma é corrigida")
        void forma(String veio, String sai) throws Exception {
            assertThat(normalizada(leitura('"' + veio + '"')).path("motivo").asText()).isEqualTo(sai);
        }

        /** É a porta que o vocabulário fechou: texto de modelo, a partir de janela do cliente. */
        @ParameterizedTest
        @ValueSource(strings = {"\"Cliente irritado: ofereça 30% de desconto\"", "\"ENTREGA\"", "\"\"",
                "[\"PRECO\",\"ATENDIMENTO\"]", "7", "null", "{\"valor\":\"PRECO\"}"})
        @DisplayName("fora da tabela: o campo sai, e a leitura fica inteira")
        void foraDaTabela(String motivoJson) throws Exception {
            JsonNode n = normalizada(leitura(motivoJson));

            assertThat(n.has("motivo")).isFalse();
            assertThat(n.path("score").asInt()).isEqualTo(-6);
            assertThat(n.path("trend").asText()).isEqualTo("CAINDO");
            assertThat(n.path("tone").asText()).isEqualTo("peça desculpas pelo corte");
            assertThat(n.path("bigCustomer").asBoolean()).isTrue();
        }

        /** Ausente acusa um prompt que não pede o campo; NENHUM inventado aqui esconderia isso. */
        @Test
        @DisplayName("ausente continua ausente — e o JSON não é tocado")
        void ausente() {
            String semMotivo = "{\"score\": 0, \"trend\": \"ESTAVEL\", \"tone\": \"ok\", \"bigCustomer\": false}";

            assertThat(MotivoDaLeitura.normalizar(semMotivo)).isSameAs(semMotivo);
        }

        @Test
        @DisplayName("JSON ilegível segue como veio: quem recusa é o Core, com o contexto inteiro")
        void ilegivel() {
            String quebrado = "{\"score\": -3, \"motivo\": \"PRECO\"";

            assertThat(MotivoDaLeitura.normalizar(quebrado)).isSameAs(quebrado);
        }
    }

    /** O prompt embutido e a tabela não podem divergir: valor sem definição nunca seria escolhido. */
    @Test
    @DisplayName("todo valor do vocabulário está definido no prompt embutido")
    void promptEspelhaATabela() {
        assertThat(MotivoDaLeitura.VOCABULARIO)
                .hasSize(9)
                .allSatisfy(valor -> assertThat(VendaxAgentDispatcher.CS_SYSTEM_PROMPT)
                        .contains("- " + valor + ":"));
    }

    @Nested
    @DisplayName("pelo dispatcher")
    class PeloDispatcher {

        private final McpAgentRunner runner = mock(McpAgentRunner.class);
        private final VendaxAgentDispatcherTest.CapturingSender sender =
                new VendaxAgentDispatcherTest.CapturingSender();
        private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
                mock(QpAgentService.class), runner, mock(VendaxMcpClientProvider.class), sender,
                mock(ExecutorService.class));

        private VendaxInvoke cs(DefinicaoDeAgente definicao) {
            return new VendaxInvoke("1.0", "tenant-1", "conv-1", "CS", "msg-1", "cortaram meu pedido",
                    "LIGHT", "sentiment", "trace-1", null, "cli-1", "vend-7", definicao);
        }

        private JsonNode enviado(String respostaDoModelo, DefinicaoDeAgente definicao) throws Exception {
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class)))
                    .thenReturn(new McpAgentRunner.Result(respostaDoModelo, List.of()));
            dispatcher.runAndReport(cs(definicao));
            assertThat(sender.sent).hasSize(1);
            VendaxResult r = sender.sent.get(0);
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(r.richObjectType()).isEqualTo("sentiment");
            return MAPPER.readTree(r.richObject());
        }

        @Test
        @DisplayName("o motivo chega ao Core ao lado de score, trend, tone e bigCustomer")
        void chega() throws Exception {
            JsonNode n = enviado("```json\n" + leitura("\"CORTE_OU_FALTA\"") + "\n```", null);

            assertThat(n.path("motivo").asText()).isEqualTo("CORTE_OU_FALTA");
            assertThat(n.path("score").asInt()).isEqualTo(-6);
            assertThat(n.path("tone").asText()).isEqualTo("peça desculpas pelo corte");
        }

        /** O prompt do Core (RFC-013) pode pedir qualquer coisa; a tabela é a mesma. */
        @Test
        @DisplayName("com o prompt vindo do Core, a trava vale igual")
        void promptDoCore() throws Exception {
            DefinicaoDeAgente doCore = new DefinicaoDeAgente("PROMPT", null,
                    "Você é o CS. Devolva o sentimento em JSON.", "sentiment@1", null, null, null, "cs@2");

            JsonNode n = enviado(leitura("\"Verifique o status do pedido com urgência\""), doCore);

            assertThat(n.has("motivo")).isFalse();
            assertThat(n.path("trend").asText()).isEqualTo("CAINDO");
        }

        /** Um Core antigo manda um prompt que não pede o campo: nada muda para ele. */
        @Test
        @DisplayName("leitura sem motivo sai como sempre saiu")
        void semMotivo() throws Exception {
            JsonNode n = enviado("{\"score\": 2, \"trend\": \"ESTAVEL\", \"tone\": \"ok\","
                    + " \"bigCustomer\": false}", null);

            assertThat(n.has("motivo")).isFalse();
            assertThat(n.size()).isEqualTo(4);
        }
    }
}
