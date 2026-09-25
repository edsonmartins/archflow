package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O prazo do envelope vale no caminho de fluxo (pedido do VendaX de 22/09/2026).
 *
 * <p>Os agentes que rodavam pelo {@code case} tinham prazo próprio: estourado, o Core recebe
 * {@code ERROR} na hora e o {@code OK} atrasado é descartado. O {@code runFluxo} não tinha prazo
 * nenhum, e o {@code validoAte} que o Core manda chegava ao envelope e era <b>descartado em
 * silêncio</b> — o record ignora o que não conhece. O modelo seguia rodando e cobrando depois de a
 * tela ter desistido, e era isso que impedia NS, US e CPA de sair do {@code switch}.</p>
 */
@DisplayName("prazo do envelope no caminho de fluxo")
class PrazoNoFluxoTest {

    private static final Map<String, Object> DOCUMENTO = Map.of(
            "id", "vendax-ns",
            "steps", List.of(Map.of("id", "consulta", "type", "mcp-agent")));

    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
            mock(QpAgentService.class), mock(McpAgentRunner.class),
            mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class), null, fluxo);

    private static String emSegundos(long segundos) {
        return OffsetDateTime.ofInstant(Instant.now().plusSeconds(segundos), ZoneOffset.UTC).toString();
    }

    private static VendaxInvoke invoke(String validoAte) {
        return new VendaxInvoke("1.0", "t1", "c1", "NS", "m1", "posso dar 8,5?", "LIGHT",
                "ns:consulta", "trace-ns", null, "cli-1", "vend-7", "ns-chave", null, List.of(),
                validoAte, new DefinicaoDeAgente("FLUXO", DOCUMENTO, null, "ns_consulta@1",
                        List.of(), Map.of(), Map.of(), "ns@1"));
    }

    private void fluxoResponde(AgentFlowRunner.Saida saida) {
        when(fluxo.executar(any(), eq(DOCUMENTO), any(), any())).thenReturn(saida);
    }

    /** O fluxo demora mais que o prazo: o dublê dorme, como um laço de modelo lento. */
    private void fluxoDemora(long ms) {
        when(fluxo.executar(any(), eq(DOCUMENTO), any(), any())).thenAnswer(chamada -> {
            Thread.sleep(ms);
            return new AgentFlowRunner.Saida("{\"frase\":\"tarde demais\"}", false);
        });
    }

    private VendaxResult unico() {
        assertThat(sender.sent).hasSize(1);
        return sender.sent.get(0);
    }

    @Nested
    @DisplayName("com prazo")
    class ComPrazo {

        @Test
        @DisplayName("respondeu dentro do prazo: OK, como sempre")
        void dentroDoPrazo() {
            fluxoResponde(new AgentFlowRunner.Saida("{\"frase\":\"pode ir a 8,5\"}", false));

            dispatcher.runAndReport(invoke(emSegundos(30)));

            assertThat(unico().status()).isEqualTo(VendaxResult.OK);
        }

        @Test
        @DisplayName("passou do prazo: ERROR na hora, com a chave do acionamento")
        void estourou() {
            fluxoDemora(2_000);

            dispatcher.runAndReport(invoke(OffsetDateTime.ofInstant(
                    Instant.now().plusMillis(150), ZoneOffset.UTC).toString()));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("não respondeu até");
            assertThat(r.idempotencyKey()).isEqualTo("ns-chave");
        }

        /**
         * O {@code OK} atrasado não é aproveitado: o Core já recebeu o {@code ERROR} com esta chave,
         * e uma segunda resposta seria resposta para uma pergunta encerrada.
         */
        @Test
        @DisplayName("o resultado que chega depois do prazo é descartado")
        void okAtrasadoNaoSai() throws Exception {
            fluxoDemora(500);

            dispatcher.runAndReport(invoke(OffsetDateTime.ofInstant(
                    Instant.now().plusMillis(100), ZoneOffset.UTC).toString()));
            Thread.sleep(900);

            assertThat(sender.sent).hasSize(1);
            assertThat(sender.sent.get(0).status()).isEqualTo(VendaxResult.ERROR);
        }

        /** Chegou tarde: nem vale começar — nada de modelo, nada de custo. */
        @Test
        @DisplayName("prazo já passado quando o invoke chega: ERROR sem executar o fluxo")
        void jaPassou() {
            dispatcher.runAndReport(invoke(emSegundos(-5)));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("já havia passado");
            verify(fluxo, never()).executar(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("sem prazo")
    class SemPrazo {

        /** Lote, e todo invoke de um Core anterior ao campo: roda até o fim. */
        @Test
        @DisplayName("validoAte nulo: nada de prazo, mesmo com o fluxo lento")
        void semValidoAte() {
            fluxoDemora(300);

            dispatcher.runAndReport(invoke(null));

            assertThat(unico().status()).isEqualTo(VendaxResult.OK);
        }

        /** Prazo ilegível não pode virar execução recusada: seria trocar atrasado por nenhum. */
        @Test
        @DisplayName("validoAte ilegível: segue sem prazo, com aviso")
        void ilegivel() {
            fluxoResponde(new AgentFlowRunner.Saida("{\"frase\":\"ok\"}", false));

            dispatcher.runAndReport(invoke("ontem à tarde"));

            assertThat(unico().status()).isEqualTo(VendaxResult.OK);
        }
    }

    @Nested
    @DisplayName("a leitura do prazo")
    class Leitura {

        @Test
        @DisplayName("aceita instante com deslocamento e em UTC")
        void formatos() {
            assertThat(dispatcher.prazoRestante(invoke("2099-01-01T00:00:00Z"))).isPositive();
            assertThat(dispatcher.prazoRestante(invoke("2099-01-01T00:00:00-03:00"))).isPositive();
            assertThat(dispatcher.prazoRestante(invoke("2000-01-01T00:00:00Z"))).isNegative();
        }

        @Test
        @DisplayName("ausente, vazio e ilegível não são prazo")
        void semPrazo() {
            assertThat(dispatcher.prazoRestante(invoke(null))).isNull();
            assertThat(dispatcher.prazoRestante(invoke("   "))).isNull();
            assertThat(dispatcher.prazoRestante(invoke("amanhã"))).isNull();
        }

        /** O que o Core manda hoje: ttl da skill somado ao instante do acionamento. */
        @Test
        @DisplayName("um prazo de 20 s deixa perto de 20 s de folga")
        void folga() {
            Duration restante = dispatcher.prazoRestante(invoke(emSegundos(20)));

            assertThat(restante).isBetween(Duration.ofSeconds(18), Duration.ofSeconds(20));
        }
    }
}
