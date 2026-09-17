package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.SemFerramentas;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A narração do dia pelo AP (VendaX, ADR-035): a contagem do Core vira uma a três frases.
 *
 * <p>O que se protege: o agente não tem ferramenta nem catálogo, roda uma volta só, recebe a
 * contagem inteira, e o resultado tem a forma que o Core confere.</p>
 */
@DisplayName("AP — narração do dia")
class NarracaoDoDiaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAVE = "ap-narracao:dia-7";
    private static final String PAYLOAD = """
            {"diaId":"dia-7","dia":"2026-09-17","agora":"16:30",
             "total":4,"cumpridas":3,"comResultado":2,"abertas":1,"naoCumpridas":0,"atrasadas":0,
             "proxima":{"cliente":"Pizzaria Bella","acao":"LIGAR","venceAs":"17:00","atrasada":false}}""";

    private final VendaxMcpClientProvider vendax = mock(VendaxMcpClientProvider.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();

    private static VendaxInvoke narracao(String reason) {
        return new VendaxInvoke("1.0", "tenant-1", null, "AP", null, null, "LIGHT", reason,
                "dia-7", PAYLOAD, null, "vend-7", CHAVE, null, null, null);
    }

    private static JsonNode rich(VendaxResult r) throws Exception {
        return MAPPER.readTree(r.richObject());
    }

    @Nested
    @DisplayName("com o laço dublado")
    class Dublado {

        private final McpAgentRunner runner = mock(McpAgentRunner.class);
        private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
                mock(QpAgentService.class), runner, vendax, sender, mock(ExecutorService.class));

        private void modeloEscreve(String texto) {
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class)))
                    .thenReturn(new McpAgentRunner.Result(texto, List.of()));
        }

        @Test
        @DisplayName("sem ferramenta, sem servidor MCP, uma volta, tier LIGHT")
        void montagem() {
            modeloEscreve("Das tarefas de hoje, 3 cumpridas.");

            dispatcher.runAndReport(narracao(NarracaoDoDia.REASON));

            ArgumentCaptor<McpAgentRunner.Options> opcoes =
                    ArgumentCaptor.forClass(McpAgentRunner.Options.class);
            verify(runner).run(eq("tenant-1"), anyString(), anyString(),
                    eq(SemFerramentas.INSTANCIA), opcoes.capture());
            McpAgentRunner.Options o = opcoes.getValue();
            assertThat(o.maxIterations()).isEqualTo(1);
            assertThat(o.access().isAllowed("obter_cliente_360"))
                    .as("segunda barreira: nenhuma tool passa")
                    .isFalse();
            assertThat(o.tier()).isEqualTo("LIGHT");
            assertThat(o.uso()).isNotNull();
            verifyNoInteractions(vendax);
        }

        /** Tudo o que a frase pode dizer está no payload; sem ele o modelo inventaria. */
        @Test
        @DisplayName("a contagem inteira chega ao modelo")
        void entrada() {
            modeloEscreve("x");

            dispatcher.runAndReport(narracao(NarracaoDoDia.REASON));

            ArgumentCaptor<String> entrada = ArgumentCaptor.forClass(String.class);
            verify(runner).run(anyString(), anyString(), entrada.capture(), any(),
                    any(McpAgentRunner.Options.class));
            assertThat(entrada.getValue())
                    .contains("vendedorRef=vend-7")
                    .contains("\"cumpridas\":3")
                    .contains("\"venceAs\":\"17:00\"")
                    .contains("Pizzaria Bella")
                    .doesNotContain("entrada=");
        }

        @Test
        @DisplayName("OK com a frase como o modelo a escreveu")
        void ok() throws Exception {
            String frase = "Bom fim de dia. Das tarefas de hoje, 3 cumpridas — e a da Pizzaria Bella "
                    + "vence às 17h. Uma ligação fecha o dia em 1.";
            modeloEscreve("  " + frase + "\n");

            dispatcher.runAndReport(narracao(NarracaoDoDia.REASON));

            VendaxResult r = sender.sent.get(0);
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(r.agent()).isEqualTo("AP");
            assertThat(r.richObjectType()).isEqualTo("ap_narracao");
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);
            assertThat(r.conversationId()).isNull();
            assertThat(rich(r).path("narracao").asText()).isEqualTo(frase);
        }

        @Test
        @DisplayName("o modelo embrulhou em {narracao}: vale o campo")
        void embrulhada() throws Exception {
            modeloEscreve("{\"narracao\":\"Das tarefas de hoje, 3 cumpridas.\"}");

            dispatcher.runAndReport(narracao(NarracaoDoDia.REASON));

            assertThat(rich(sender.sent.get(0)).path("narracao").asText())
                    .isEqualTo("Das tarefas de hoje, 3 cumpridas.");
        }

        @Test
        @DisplayName("nada redigido: ERROR com a mesma chave — a tela segue com os números")
        void vazia() {
            modeloEscreve("   ");

            dispatcher.runAndReport(narracao(NarracaoDoDia.REASON));

            VendaxResult r = sender.sent.get(0);
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);
        }

        @Test
        @DisplayName("falha do provedor: ERROR com o motivo e a mesma chave")
        void falha() {
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class)))
                    .thenThrow(new IllegalStateException("provedor recusou o pedido"));

            dispatcher.runAndReport(narracao(NarracaoDoDia.REASON));

            VendaxResult r = sender.sent.get(0);
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("provedor recusou");
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);
        }

        @Test
        @DisplayName("AP com outro reason: ERROR 'não implementado', sem chamar o modelo")
        void outroReason() {
            dispatcher.runAndReport(narracao("ap:outro"));

            VendaxResult r = sender.sent.get(0);
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("ainda não é executado");
            verify(runner, never()).run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class));
        }
    }

    /**
     * Com o laço de verdade: o que chega ao modelo e o que o uso relata. Critério 5 do pedido —
     * nenhuma tool no catálogo, uma volta, nenhuma chamada.
     */
    @Nested
    @DisplayName("com o laço de verdade")
    class LacoReal {

        private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
                .provider("openrouter").model("google/gemini-2.5-flash-lite").build();

        private final List<ChatRequest> pedidos = new ArrayList<>();

        private VendaxAgentDispatcher dispatcher(ChatResponse resposta) {
            ChatModel modelo = new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest request) {
                    pedidos.add(request);
                    return resposta;
                }
            };
            LLMConfigResolver resolver = new LLMConfigResolver() {
                @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
                @Override public ChatModel resolveModel(LLMResolutionRequest r) { return modelo; }
            };
            return new VendaxAgentDispatcher(mock(QpAgentService.class),
                    new McpAgentRunner(resolver, CONFIG), vendax, sender, mock(ExecutorService.class));
        }

        @Test
        @DisplayName("catálogo vazio, uma volta: llmTurns 1 e toolCalls 0 no uso")
        void umaVoltaSemCatalogo() throws Exception {
            dispatcher(ChatResponse.builder()
                    .aiMessage(AiMessage.from("Das tarefas de hoje, 3 cumpridas de 4."))
                    .tokenUsage(new TokenUsage(900, 30))
                    .build())
                    .runAndReport(narracao(NarracaoDoDia.REASON));

            assertThat(pedidos).hasSize(1);
            assertThat(pedidos.get(0).toolSpecifications()).isEmpty();
            assertThat(((UserMessage) pedidos.get(0).messages().get(1)).singleText())
                    .contains("\"total\":4");

            VendaxResult r = sender.sent.get(0);
            assertThat(rich(r).path("narracao").asText()).isEqualTo("Das tarefas de hoje, 3 cumpridas de 4.");
            assertThat(r.uso().llmTurns()).isEqualTo(1);
            assertThat(r.uso().toolCalls()).isZero();
            assertThat(r.uso().tokens()).isEqualTo(930);
            assertThat(r.uso().execucaoId()).isNotBlank();
            verifyNoInteractions(vendax);
        }

        /** Mesmo sem tool no catálogo, um modelo pode inventar uma chamada. Ela não sai daqui. */
        @Test
        @DisplayName("tool inventada pelo modelo: negada, sem segunda volta, e ERROR")
        void toolInventada() {
            dispatcher(ChatResponse.builder()
                    .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("c1").name("obter_cliente_360").arguments("{}").build()))
                    .build())
                    .runAndReport(narracao(NarracaoDoDia.REASON));

            assertThat(pedidos).as("uma volta só").hasSize(1);
            VendaxResult r = sender.sent.get(0);
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.uso().toolCalls()).as("negada não vai ao servidor").isZero();
        }
    }
}
