package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Todo consumo chega ao Core, e chega uma vez (pedido do VendaX de 16/09).
 *
 * <p>Três buracos fechados: execução sem result, fluxo suspenso e o que o laço gasta depois de um
 * {@code ERROR} por prazo. E o {@code tier} passa a valer também no QP e no CS.</p>
 */
@DisplayName("uso completo: sem buracos, sem contagem dupla")
class UsoCompletoTest {

    private static final String MODELO = "openrouter/google/gemini-2.5-flash-lite";

    private final QpAgentService qp = mock(QpAgentService.class);
    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(qp, runner,
            mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class), null, fluxo);

    {
        dispatcher.setPrecos(new TabelaDePrecos(Map.of(MODELO,
                new TabelaDePrecos.Preco(new BigDecimal("50"), new BigDecimal("200")))));
    }

    private static VendaxInvoke invoke(String agente, String reason, String tier) {
        return new VendaxInvoke("1.0", "t1", "c1", agente, "m1", "bom dia", tier, reason,
                "trace", null, "cli-1", "vend-1", "chave-" + agente, null, null, null);
    }

    /** O QP dublado alimenta o contador que o dispatcher mandou, como o runner real faria. */
    @SuppressWarnings("unchecked")
    private void qpGastaERetorna(String quote) {
        when(qp.quote(any(), nullable(String.class), any())).thenAnswer(chamada -> {
            UnaryOperator<McpAgentRunner.Options> ajuste = chamada.getArgument(2);
            ContadorDeUso uso = ajuste.apply(new McpAgentRunner.Options(ToolAccessPolicy.allowAll())).uso();
            uso.somar("openrouter", MODELO, 4600, 400);
            uso.somarChamadaDeTool();
            return new QpAgentService.QpResult("oi", List.of(), quote, "qp-1");
        });
    }

    @Nested
    @DisplayName("execução sem result")
    class SemResult {

        @Test
        @DisplayName("QP sem cotação relata SEM_RESULT, com execucaoId e detalhamento")
        void qpSemCotacao() {
            qpGastaERetorna(null);

            dispatcher.runAndReport(invoke("QP", "teste", "STRONG"));

            assertThat(sender.sent).isEmpty();
            assertThat(sender.relatos).singleElement().satisfies(r -> {
                assertThat(r.motivo()).isEqualTo(VendaxUsoRelato.Motivo.SEM_RESULT);
                assertThat(r.idempotencyKey()).isEqualTo("chave-QP");
                assertThat(r.uso().execucaoId()).isNotBlank();
                assertThat(r.uso().tokens()).isEqualTo(5000);
                assertThat(r.uso().inputTokens()).isEqualTo(4600);
                assertThat(r.uso().outputTokens()).isEqualTo(400);
                assertThat(r.uso().llmTurns()).isEqualTo(1);
                assertThat(r.uso().toolCalls()).isEqualTo(1);
                assertThat(r.uso().provider()).isEqualTo("openrouter");
                // 4600 × 50 + 400 × 200 = 310.000 → 0,31 centavo, para cima
                assertThat(r.uso().costCents()).isEqualTo(1);
            });
        }

        /** O uso do result segue no result; a rota de relato não o repete. */
        @Test
        @DisplayName("QP com cotação: o uso vai no result, e nenhum relato sai")
        void qpComCotacao() {
            qpGastaERetorna("{\"total\":10}");

            dispatcher.runAndReport(invoke("QP", "teste", "STRONG"));

            assertThat(sender.relatos).isEmpty();
            assertThat(sender.sent).singleElement()
                    .satisfies(r -> assertThat(r.uso().execucaoId()).isNotBlank());
        }

        @Test
        @DisplayName("QP que não chamou o modelo: nada a relatar")
        void qpSemConsumo() {
            when(qp.quote(any(), nullable(String.class), any()))
                    .thenReturn(new QpAgentService.QpResult("oi", List.of(), null, "qp-1"));

            dispatcher.runAndReport(invoke("QP", "teste", "STRONG"));

            assertThat(sender.relatos).isEmpty();
        }

        @Test
        @DisplayName("fluxo suspenso relata SUSPENSO com o gasto até ali")
        void fluxoSuspenso() {
            when(fluxo.executar(any(), any(), any(), any())).thenAnswer(chamada -> {
                ContadorDeUso uso = chamada.getArgument(3);
                uso.somar("openrouter", MODELO, 1000, 100);
                return new AgentFlowRunner.Saida("proposta parcial", true);
            });
            VendaxInvoke comFluxo = new VendaxInvoke("1.0", "t1", "c1", "CS", "m1", "x", "LIGHT",
                    "teste", "trace", null, "cli-1", "vend-1", "chave-CS", null, null,
                    new DefinicaoDeAgente("FLUXO", Map.of("steps", List.of()), null, "sentiment@1",
                            List.of(), Map.of(), Map.of(), "cs@2"));

            dispatcher.runAndReport(comFluxo);

            assertThat(sender.sent).isEmpty();
            assertThat(sender.relatos).singleElement().satisfies(r -> {
                assertThat(r.motivo()).isEqualTo(VendaxUsoRelato.Motivo.SUSPENSO);
                assertThat(r.uso().tokens()).isEqualTo(1100);
            });
        }
    }

    /**
     * O laço não para no prazo: a chamada de modelo em voo termina e é cobrada. O ERROR leva o
     * gasto até o prazo; o relato APOS_PRAZO leva só o resto — com o mesmo execucaoId, para o Core
     * juntar os dois sem contar nada duas vezes.
     */
    @Test
    @DisplayName("prazo estourado: ERROR com o gasto até o prazo, APOS_PRAZO com o resto")
    void aposPrazo() throws Exception {
        dispatcher.setPrazoInterativo(Duration.ofMillis(200));
        when(runner.run(anyString(), anyString(), anyString(), any(), any(McpAgentRunner.Options.class)))
                .thenAnswer(chamada -> {
                    ContadorDeUso uso = chamada.<McpAgentRunner.Options>getArgument(4).uso();
                    uso.somar("openrouter", MODELO, 600, 40);
                    long fim = System.nanoTime() + 600_000_000L;
                    while (System.nanoTime() < fim) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            // a chamada de modelo em voo não obedece à interrupção
                        }
                    }
                    uso.somar("openrouter", MODELO, 150, 22);
                    // o runner real restaura a interrupção ao sair — o relato não pode depender
                    // desta thread
                    Thread.currentThread().interrupt();
                    return new McpAgentRunner.Result("tarde", List.of());
                });

        dispatcher.runAndReport(invoke("NS", VendaxAgentDispatcher.NS_CONSULTA, "LIGHT"));

        VendaxResult erro = sender.sent.get(0);
        assertThat(erro.status()).isEqualTo(VendaxResult.ERROR);
        assertThat(erro.uso().tokens()).as("só o gasto até o prazo").isEqualTo(640);

        esperarRelato();
        VendaxUsoRelato depois = sender.relatos.get(0);
        assertThat(depois.motivo()).isEqualTo(VendaxUsoRelato.Motivo.APOS_PRAZO);
        assertThat(depois.uso().tokens()).as("só o gasto depois do ERROR").isEqualTo(172);
        assertThat(depois.uso().llmTurns()).isEqualTo(1);
        assertThat(depois.uso().execucaoId()).isEqualTo(erro.uso().execucaoId());
        assertThat(sender.sent).as("nenhum result tardio").hasSize(1);
        assertThat(sender.relatoEmThreadInterrompida)
                .as("a thread do laço foi interrompida; o HTTP do relato falharia nela")
                .containsOnly(false);
    }

    private void esperarRelato() {
        long limite = System.nanoTime() + 5_000_000_000L;
        while (sender.relatos.isEmpty() && System.nanoTime() < limite) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("prazo estourado sem gasto posterior: nenhum relato")
    void aposPrazoSemGasto() throws Exception {
        dispatcher.setPrazoInterativo(Duration.ofMillis(100));
        when(runner.run(anyString(), anyString(), anyString(), any(), any(McpAgentRunner.Options.class)))
                .thenAnswer(chamada -> {
                    chamada.<McpAgentRunner.Options>getArgument(4).uso()
                            .somar("openrouter", MODELO, 600, 40);
                    Thread.sleep(2_000);
                    return new McpAgentRunner.Result("tarde", List.of());
                });

        dispatcher.runAndReport(invoke("NS", VendaxAgentDispatcher.NS_CONSULTA, "LIGHT"));
        Thread.sleep(400);

        assertThat(sender.sent.get(0).uso().tokens()).isEqualTo(640);
        assertThat(sender.relatos).as("interrompido sem gastar mais: nada a relatar").isEmpty();
    }

    @Nested
    @DisplayName("tier no caminho por nome")
    class Tier {

        @Test
        @DisplayName("QP leva o tier do invoke")
        @SuppressWarnings("unchecked")
        void qp() {
            when(qp.quote(any(), nullable(String.class), any()))
                    .thenReturn(new QpAgentService.QpResult("oi", List.of(), null, "qp-1"));

            dispatcher.runAndReport(invoke("QP", "teste", "LIGHT"));

            ArgumentCaptor<UnaryOperator<McpAgentRunner.Options>> ajuste =
                    ArgumentCaptor.forClass(UnaryOperator.class);
            verify(qp).quote(any(), nullable(String.class), ajuste.capture());
            assertThat(ajuste.getValue().apply(new McpAgentRunner.Options(ToolAccessPolicy.allowAll()))
                    .tier()).isEqualTo("LIGHT");
        }

        @Test
        @DisplayName("CS leva o tier do invoke")
        void cs() {
            when(runner.run(anyString(), anyString(), anyString(), any(), any(McpAgentRunner.Options.class)))
                    .thenReturn(new McpAgentRunner.Result("{\"score\":0}", List.of()));

            dispatcher.runAndReport(invoke("CS", "teste", "STRONG"));

            ArgumentCaptor<McpAgentRunner.Options> captor =
                    ArgumentCaptor.forClass(McpAgentRunner.Options.class);
            verify(runner).run(anyString(), anyString(), anyString(), any(), captor.capture());
            assertThat(captor.getValue().tier()).isEqualTo("STRONG");
        }

        @Test
        @DisplayName("invoke sem tier não apaga o que as opções já tinham")
        void semTier() {
            McpAgentRunner.Options base = new McpAgentRunner.Options(ToolAccessPolicy.allowAll())
                    .comTier("STRONG");

            assertThat(VendaxAgentDispatcher.daExecucao(base, invoke("CS", "teste", null),
                    new ContadorDeUso()).tier()).isEqualTo("STRONG");
        }
    }
}
