package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "CS não devolveu um sentimento em JSON" — 21 de 48 leituras na bancada do VendaX de 21/09/2026.
 *
 * <p>O que se protege: uma nova tentativa, e só uma; o Core recebe o começo do que veio, para o
 * próximo diagnóstico não depender de alguém ler o log daqui; e prosa com chaves não passa mais por
 * sentimento.</p>
 */
@DisplayName("CS — sentimento em JSON")
class SentimentoDoCsTest {

    private static final String SENTIMENTO =
            "{\"score\": -3, \"trend\": \"CAINDO\", \"tone\": \"ok\", \"bigCustomer\": false}";

    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
            mock(QpAgentService.class), runner, mock(VendaxMcpClientProvider.class), sender,
            mock(ExecutorService.class));

    private static VendaxInvoke cs() {
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", "CS", "msg-1", null, "LIGHT",
                "sentiment_window", "trace-cs", "{\"messages\":[{\"direction\":\"INBOUND\","
                        + "\"type\":\"text\",\"text\":\"cadê meu pedido?\"}]}", "cli-1", "vend-7", null);
    }

    private void modeloResponde(String primeira, String... seguintes) {
        var chamada = when(runner.run(anyString(), anyString(), anyString(), any(),
                any(McpAgentRunner.Options.class)))
                .thenReturn(new McpAgentRunner.Result(primeira, List.of()));
        for (String s : seguintes) {
            chamada = chamada.thenReturn(new McpAgentRunner.Result(s, List.of()));
        }
    }

    private VendaxResult unico() {
        assertThat(sender.sent).hasSize(1);
        return sender.sent.get(0);
    }

    @Nested
    @DisplayName("a nova tentativa")
    class NovaTentativa {

        @Test
        @DisplayName("acertou de primeira: uma chamada só")
        void deOPrimeira() {
            modeloResponde(SENTIMENTO);

            dispatcher.runAndReport(cs());

            assertThat(unico().status()).isEqualTo(VendaxResult.OK);
            verify(runner, times(1)).run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class));
        }

        /** O padrão que deixou rastro na bancada: resposta vazia, e a seguinte certa. */
        @Test
        @DisplayName("veio vazia: tenta de novo, e a segunda vale")
        void vaziaEDepoisCerta() {
            modeloResponde("", SENTIMENTO);

            dispatcher.runAndReport(cs());

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(r.richObjectType()).isEqualTo("sentiment");
            verify(runner, times(2)).run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class));
        }

        @Test
        @DisplayName("falhou duas vezes: ERROR, e não uma terceira chamada")
        void duasFalhas() {
            modeloResponde(null, "   ");

            dispatcher.runAndReport(cs());

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("2 tentativas").contains("(vazia)");
            verify(runner, times(2)).run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class));
        }

        /** Mesmo prompt e mesma entrada: a repetição não é uma pergunta diferente. */
        @Test
        @DisplayName("a segunda chamada leva o mesmo prompt, a mesma entrada e o mesmo contador")
        void mesmaChamada() {
            modeloResponde("", SENTIMENTO);

            dispatcher.runAndReport(cs());

            var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
            var entradas = org.mockito.ArgumentCaptor.forClass(String.class);
            var opcoes = org.mockito.ArgumentCaptor.forClass(McpAgentRunner.Options.class);
            verify(runner, times(2)).run(anyString(), prompts.capture(), entradas.capture(), any(),
                    opcoes.capture());
            assertThat(prompts.getAllValues().get(0)).isEqualTo(prompts.getAllValues().get(1));
            assertThat(entradas.getAllValues().get(0)).isEqualTo(entradas.getAllValues().get(1));
            assertThat(opcoes.getAllValues().get(0).uso()).isSameAs(opcoes.getAllValues().get(1).uso());
        }
    }

    @Nested
    @DisplayName("o que o Core recebe no ERROR")
    class NoErro {

        @Test
        @DisplayName("o começo do que veio, numa linha")
        void trecho() {
            modeloResponde("Não consegui verificar os eventos.\nVou tentar depois.",
                    "Não consegui verificar os eventos.\nVou tentar depois.");

            dispatcher.runAndReport(cs());

            assertThat(unico().error())
                    .contains("\"Não consegui verificar os eventos. Vou tentar depois.\"")
                    .doesNotContain("\n");
        }

        /** Um JSON truncado se denuncia pelo fim — e o fim é o que um corte lá do Core perderia. */
        @Test
        @DisplayName("cabe nos 255 caracteres de resultado_bancada.erro, mesmo com resposta longa")
        void cabeNoCampoDoCore() {
            String longa = "{\"score\": -7, \"trend\": \"CAINDO\", \"tone\": \"" + "x".repeat(900);
            modeloResponde(longa, longa);

            dispatcher.runAndReport(cs());

            assertThat(unico().error()).hasSizeLessThanOrEqualTo(255).endsWith("…\"");
        }
    }

    @Nested
    @DisplayName("o que conta como sentimento")
    class Extracao {

        @Test
        @DisplayName("dentro de cerca de código, e com prosa em volta, vale")
        void cercaEProsa() {
            assertThat(VendaxAgentDispatcher.sentimentoEm("Aqui está:\n```json\n" + SENTIMENTO + "\n```"))
                    .isEqualTo(SENTIMENTO);
        }

        /** Antes passava: o Core recebia prosa como sentimento e a recusava lá, sem contexto. */
        @Test
        @DisplayName("prosa com chaves não é sentimento")
        void prosaComChaves() {
            assertThat(VendaxAgentDispatcher.sentimentoEm("use {cliente} e depois {pedido}")).isNull();
        }

        @Test
        @DisplayName("JSON truncado não é sentimento")
        void truncado() {
            assertThat(VendaxAgentDispatcher.sentimentoEm("{\"score\": -3, \"trend\": \"CAI")).isNull();
        }

        /**
         * A extração vai do primeiro "{" ao último "}": duas leituras soltas na mesma resposta viram
         * um trecho que não se lê. Antes isso chegava ao Core como sentimento.
         */
        @Test
        @DisplayName("duas leituras soltas na mesma resposta não são um sentimento")
        void duasLeituras() {
            assertThat(VendaxAgentDispatcher.sentimentoEm(
                    "{\"score\": 1} ou talvez {\"score\": -2}")).isNull();
        }
    }
}
