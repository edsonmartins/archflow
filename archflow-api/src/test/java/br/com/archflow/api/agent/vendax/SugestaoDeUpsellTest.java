package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.ToolTrust;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O upsell pré-fechamento pelo US (VendaX, ADR-023 e ADR-038).
 *
 * <p>Medido em 18/09 no homolog do VendaX: <b>544 acionamentos de US</b> entre 03 e 24/08 caíram no
 * ramo "agente não implementado" do dispatcher, e nenhuma sugestão chegou ao vendedor — sem que nada
 * do lado do Core acusasse. O primeiro teste aqui é o que teria pegado isso.</p>
 *
 * <p>O que o resto protege: o {@code intervencaoId} vem da chamada da tool, nunca do modelo; o item
 * escolhido é conferido contra as candidatas; e nenhum número vai no envelope.</p>
 */
@DisplayName("US — sugestão de upsell")
class SugestaoDeUpsellTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERVENCAO = "iv-77";

    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
            mock(QpAgentService.class), runner, mock(VendaxMcpClientProvider.class), sender,
            mock(ExecutorService.class));

    private static VendaxInvoke invoke(String reason) {
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", "US", "msg-9", null, "STRONG", reason,
                "trace-us", "{\"pedidoEmMontagem\":[\"39687:1\",\"41120:2\"],"
                        + "\"momentoDaConversa\":\"ANTES_FECHAMENTO\"}",
                "cli-1", "vend-7", null, "task-3", null, null);
    }

    /** A resposta da tool, na forma que o Core devolve. */
    private static McpAgentRunner.ToolCall sugeriu(String corpo) {
        return new McpAgentRunner.ToolCall(SugestaoDeUpsell.TOOL,
                Map.of("clienteRef", "cli-1", "vendedorRef", "vend-7"),
                corpo, false, ToolTrust.UNTRUSTED);
    }

    private static String comSugestoes() {
        return """
                {"sugestoes":[
                  {"skuRef":"77012:1","descricao":"Filtro de papel 103 c/30",
                   "argumento":"costuma vir junto","probabilidade":0.42,"suporte":7,
                   "margemResultanteCents":1850,"disponivel":true},
                  {"skuRef":"88100:2","descricao":"Coador","argumento":"leva junto",
                   "probabilidade":0.20,"suporte":3,"margemResultanteCents":900,"disponivel":true}],
                 "grupoControle":false,"intervencaoId":"iv-77","motivo":null}""";
    }

    private void lacoDevolve(McpAgentRunner.Result resultado) {
        when(runner.run(anyString(), anyString(), anyString(), any(),
                any(McpAgentRunner.Options.class))).thenReturn(resultado);
    }

    private VendaxResult unico() {
        assertThat(sender.sent).hasSize(1);
        return sender.sent.get(0);
    }

    private static JsonNode rich(VendaxResult r) throws Exception {
        return MAPPER.readTree(r.richObject());
    }

    @Test
    @DisplayName("US com reason=quote-draft é executado — e não cai em 'não implementado'")
    void executado() {
        lacoDevolve(new McpAgentRunner.Result(
                "{\"skuRef\":\"77012:1\",\"argumento\":\"sai em 42 % dos pedidos deste cliente\"}",
                List.of(sugeriu(comSugestoes()))));

        dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

        VendaxResult r = unico();
        assertThat(r.status())
                .as("544 acionamentos morreram no ramo padrão do switch entre 03 e 24/08")
                .isEqualTo(VendaxResult.OK);
        assertThat(r.richObjectType()).isEqualTo("suggestion");
    }

    @Nested
    @DisplayName("o rich object")
    class RichObject {

        @Test
        @DisplayName("type, schemaVersion e payload com id, item e frase")
        void forma() throws Exception {
            lacoDevolve(new McpAgentRunner.Result(
                    "{\"skuRef\":\"77012:1\",\"argumento\":\"sai em 42 % dos pedidos deste cliente\"}",
                    List.of(sugeriu(comSugestoes()))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            JsonNode rich = rich(unico());
            assertThat(rich.path("type").asText()).isEqualTo("suggestion");
            assertThat(rich.path("schemaVersion").asText()).isEqualTo("1.0");
            JsonNode payload = rich.path("payload");
            assertThat(payload.path("intervencaoId").asText()).isEqualTo(INTERVENCAO);
            assertThat(payload.path("skuRef").asText()).isEqualTo("77012:1");
            assertThat(payload.path("argumento").asText())
                    .isEqualTo("sai em 42 % dos pedidos deste cliente");
            assertThat(payload.size())
                    .as("número que o agente mandar é descartado pelo Core: não se manda")
                    .isEqualTo(3);
            assertThat(unico().idempotencyKey()).isEqualTo("us:trace-us");
        }

        /** O id amarra a sugestão à intervenção do Core: do modelo, seria um id inventado. */
        @Test
        @DisplayName("o intervencaoId vem da tool, mesmo que o modelo mande outro")
        void idVemDaTool() throws Exception {
            lacoDevolve(new McpAgentRunner.Result(
                    "{\"skuRef\":\"77012:1\",\"argumento\":\"costuma vir junto\","
                            + "\"intervencaoId\":\"iv-inventada\"}",
                    List.of(sugeriu(comSugestoes()))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            assertThat(rich(unico()).path("payload").path("intervencaoId").asText())
                    .isEqualTo(INTERVENCAO);
        }

        @Test
        @DisplayName("a segunda candidata também vale — quem escolhe é o modelo, dentro da lista")
        void escolheOutra() throws Exception {
            lacoDevolve(new McpAgentRunner.Result(
                    "{\"skuRef\":\"88100:2\",\"argumento\":\"leva junto\"}",
                    List.of(sugeriu(comSugestoes()))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            assertThat(rich(unico()).path("payload").path("skuRef").asText()).isEqualTo("88100:2");
        }
    }

    @Nested
    @DisplayName("quando não há o que sugerir")
    class SemSugestao {

        @Test
        @DisplayName("lista vazia: ERROR curto com o motivo da tool")
        void vazia() {
            lacoDevolve(new McpAgentRunner.Result("SEM_SUGESTAO", List.of(sugeriu(
                    "{\"sugestoes\":[],\"grupoControle\":false,\"intervencaoId\":\"iv-9\","
                            + "\"motivo\":\"SEM_BASE_DE_AFINIDADE\"}"))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("SEM_BASE_DE_AFINIDADE");
            assertThat(r.idempotencyKey()).isEqualTo("us:trace-us");
        }

        /** Exibir o braço de controle destruiria a série do lift, e sem nada acusando. */
        @Test
        @DisplayName("grupo de controle: nada é exibido, mesmo com intervencaoId")
        void grupoDeControle() {
            lacoDevolve(new McpAgentRunner.Result("SEM_SUGESTAO", List.of(sugeriu(
                    "{\"sugestoes\":[],\"grupoControle\":true,\"intervencaoId\":\"iv-9\"}"))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("controle");
            assertThat(r.richObject()).isNull();
        }
    }

    @Nested
    @DisplayName("o que o agente não decide sozinho")
    class NaoDecide {

        /** Escolher fora da lista é o agente decidindo o que oferecer; o Core recusaria. */
        @Test
        @DisplayName("item fora das candidatas: ERROR dizendo qual era")
        void foraDasCandidatas() {
            lacoDevolve(new McpAgentRunner.Result(
                    "{\"skuRef\":\"99999:9\",\"argumento\":\"leva esse\"}",
                    List.of(sugeriu(comSugestoes()))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            assertThat(unico().error()).contains("fora das candidatas").contains("99999:9");
        }

        @Test
        @DisplayName("sem consultar a tool, não há sugestão")
        void semTool() {
            lacoDevolve(new McpAgentRunner.Result(
                    "{\"skuRef\":\"77012:1\",\"argumento\":\"confia em mim\"}", List.of()));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            assertThat(unico().error()).contains("não consultou");
        }

        @Test
        @DisplayName("escolha sem frase: ERROR")
        void semArgumento() {
            lacoDevolve(new McpAgentRunner.Result(
                    "{\"skuRef\":\"77012:1\",\"argumento\":\"  \"}",
                    List.of(sugeriu(comSugestoes()))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            assertThat(unico().error()).contains("não escreveu o argumento");
        }

        @Test
        @DisplayName("resposta que não é JSON: ERROR")
        void semJson() {
            lacoDevolve(new McpAgentRunner.Result("acho que o filtro combina",
                    List.of(sugeriu(comSugestoes()))));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            assertThat(unico().error()).contains("escolha em JSON");
        }
    }

    @Nested
    @DisplayName("como o laço é montado")
    class Montagem {

        @Test
        @DisplayName("só sugerir_itens, tier do invoke, contador e task de origem")
        void opcoes() {
            lacoDevolve(new McpAgentRunner.Result("{}", List.of()));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            ArgumentCaptor<McpAgentRunner.Options> captor =
                    ArgumentCaptor.forClass(McpAgentRunner.Options.class);
            verify(runner).run(anyString(), anyString(), anyString(), any(), captor.capture());
            McpAgentRunner.Options o = captor.getValue();
            assertThat(o.access().isAllowed(SugestaoDeUpsell.TOOL)).isTrue();
            assertThat(o.access().isAllowed("firmar_cotacao")).isFalse();
            assertThat(o.tier()).isEqualTo("STRONG");
            assertThat(o.maxIterations()).isLessThanOrEqualTo(4);
            assertThat(o.uso()).isNotNull();
        }

        /** Os dois campos do payload são os argumentos da tool: sem eles o agente inventaria. */
        @Test
        @DisplayName("o payload chega ao modelo")
        void entrada() {
            lacoDevolve(new McpAgentRunner.Result("{}", List.of()));

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            ArgumentCaptor<String> entrada = ArgumentCaptor.forClass(String.class);
            verify(runner).run(anyString(), anyString(), entrada.capture(), any(),
                    any(McpAgentRunner.Options.class));
            assertThat(entrada.getValue())
                    .contains("clienteRef=cli-1")
                    .contains("vendedorRef=vend-7")
                    .contains("\"pedidoEmMontagem\":[\"39687:1\",\"41120:2\"]")
                    .contains("\"momentoDaConversa\":\"ANTES_FECHAMENTO\"");
        }

        @Test
        @DisplayName("US com outro reason: ERROR 'não implementado', sem chamar o modelo")
        void outroReason() {
            dispatcher.runAndReport(invoke("us:outro"));

            assertThat(unico().error()).contains("ainda não é executado");
            verify(runner, never()).run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class));
        }

        /** Interativo: o vendedor está olhando a cotação que acabou de sair. */
        @Test
        @DisplayName("passou do prazo: ERROR na hora, com a chave do acionamento")
        void prazo() {
            dispatcher.setPrazoInterativo(Duration.ofMillis(150));
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class))).thenAnswer(chamada -> {
                        Thread.sleep(1_000);
                        return new McpAgentRunner.Result("{}", List.of());
                    });

            dispatcher.runAndReport(invoke(SugestaoDeUpsell.REASON));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("não respondeu");
            assertThat(r.idempotencyKey()).isEqualTo("us:trace-us");
        }
    }
}
