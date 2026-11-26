package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.SaidaDeTexto;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O que uma execução gastou chega ao Core <b>exatamente uma vez</b>, em todo desfecho
 * (RCHFLW-166, RCHFLW-167, RCHFLW-171).
 *
 * <p>O motor aqui é um dublê com o comportamento que importa do {@code DefaultFlowEngine}: a
 * execução devolve uma future que só completa quando a travessia termina, o passo registra o turno
 * no contador da execução ao terminar, e cancelar a future não para a travessia (ela vem de
 * {@code supplyAsync}). Era esse último ponto que fazia o gasto do passo em voo cair num contador
 * que ninguém mais lia.</p>
 */
@DisplayName("uso relatado exatamente uma vez")
class UsoRelatadoUmaVezTest {

    private static final Map<String, Object> DOCUMENTO = Map.of(
            "id", "qa2", "steps", List.of(Map.of("id", "agente", "type", "mcp-agent")));

    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final FlowEngine engine = mock(FlowEngine.class);
    /** O contador que o fluxo recebeu — o mesmo que o dispatcher lê. */
    private final AtomicReference<ContadorDeUso> contador = new AtomicReference<>();
    private final AtomicReference<SaidaDeTexto> saidaDoFluxo = new AtomicReference<>();
    private final CountDownLatch executando = new CountDownLatch(1);

    private VendaxAgentDispatcher dispatcher() {
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        AgentFlowRunner fluxo = new AgentFlowRunner(deserializer, provider, mock(FlowRepository.class), null);
        return new VendaxAgentDispatcher(mock(QpAgentService.class), mock(McpAgentRunner.class),
                mock(VendaxMcpClientProvider.class), sender, mock(ExecutorService.class), null, fluxo);
    }

    /**
     * O passo gasta {@code antes} já ao começar e {@code depois} ao terminar, {@code ms} depois — o
     * turno de modelo que estava em voo quando o prazo venceu.
     */
    private void passoQueTermina(long ms, boolean gastaAntes) {
        when(engine.execute(any(), any())).thenAnswer(chamada -> {
            ExecutionContext ctx = chamada.getArgument(1);
            ContadorDeUso uso = ContadorDeUso.de(ctx).orElseThrow();
            contador.set(uso);
            if (gastaAntes) {
                uso.somar("openrouter", "openrouter/m", 1000, 100);
            }
            CompletableFuture<FlowResult> motor = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                dormir(ms);
                uso.somar("openrouter", "openrouter/m", 1000, 500);
                motor.complete(resultado(ExecutionStatus.COMPLETED, Map.of("text", "{\"ok\":true}")));
            });
            executando.countDown();
            return motor;
        });
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Repete a asserção até ela passar ou dar 5 s — o relato depois do prazo sai noutra thread. */
    private static void esperar(Runnable assercao) {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                assercao.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > limite) {
                    throw e;
                }
                dormir(20);
            }
        }
    }

    private static FlowResult resultado(ExecutionStatus status, Object saida) {
        return new FlowResult() {
            @Override public Optional<Object> getOutput() { return Optional.ofNullable(saida); }
            @Override public ExecutionStatus getStatus() { return status; }
            @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
            @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
        };
    }

    private static VendaxInvoke invoke(String validoAte, Map<String, Object> politica, String saidaSchema) {
        return new VendaxInvoke("1.0", "t1", "qa2v-conv", "QA2", "m1", "texto longo", "LIGHT", "qa2",
                "trace-qa2", null, null, null, "qa2-chave", null, List.of(), validoAte,
                new DefinicaoDeAgente("FLUXO", DOCUMENTO, null, saidaSchema, List.of(), Map.of(),
                        politica, "qa2@1"));
    }

    private static String daquiA(long ms) {
        return OffsetDateTime.ofInstant(Instant.now().plusMillis(ms), ZoneOffset.UTC).toString();
    }

    @Nested
    @DisplayName("RCHFLW-167: estouro de prazo")
    class Prazo {

        @Test
        @DisplayName("validoAte: o turno que termina depois do prazo vai como APOS_PRAZO, e o motor é parado")
        void validoAteRelataOQueVeioDepois() {
            passoQueTermina(800, false);

            dispatcher().runAndReport(invoke(daquiA(300), Map.of(), "manual@1"));

            assertThat(sender.sent).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
                assertThat(r.error()).contains("não respondeu até");
                assertThat(r.uso()).as("nada tinha sido gasto até o prazo").isNull();
            });
            esperar(() -> assertThat(sender.relatos)
                    .as("antes: nenhum relato — o turno de 1500 tokens nunca chegava ao Core")
                    .singleElement().satisfies(rel -> {
                        assertThat(rel.motivo()).isEqualTo(VendaxUsoRelato.Motivo.APOS_PRAZO);
                        assertThat(rel.uso().tokens()).isEqualTo(1500);
                        assertThat(rel.uso().execucaoId()).isEqualTo(contador.get().execucaoId());
                        assertThat(rel.idempotencyKey()).isEqualTo("qa2-chave");
                    }));
            assertThat(sender.relatoEmThreadInterrompida).containsOnly(false);
            verify(engine).cancel(anyString());
        }

        /**
         * A thread do {@code validoAte} desiste de esperar o motor no teto do {@code timeout_ms}
         * (700 ms) e termina antes dele (1400 ms). Pendurado nela, o relato disparava vazio aos
         * 700 ms e o turno anotado aos 1400 ms se perdia.
         */
        @Test
        @DisplayName("validoAte: o turno que termina depois também do timeout_ms ainda vai como APOS_PRAZO")
        void validoAteComOMotorPassandoDoTimeout() {
            passoQueTermina(1_400, false);

            dispatcher().runAndReport(invoke(daquiA(300), Map.of("timeout_ms", 700), "manual@1"));

            assertThat(sender.sent).singleElement()
                    .satisfies(r -> assertThat(r.error()).contains("não respondeu até"));
            esperar(() -> assertThat(sender.relatos)
                    .as("antes: nenhum relato — o relato esperava a thread do prazo, não o motor")
                    .singleElement().satisfies(rel -> {
                        assertThat(rel.motivo()).isEqualTo(VendaxUsoRelato.Motivo.APOS_PRAZO);
                        assertThat(rel.uso().tokens()).isEqualTo(1500);
                    }));
        }

        /**
         * O {@code validoAte} e o {@code timeout_ms} vencendo quase juntos: o dispatcher toma o ramo
         * do {@code validoAte}, e a thread do fluxo termina com o {@code PrazoDoFluxo} — cuja
         * execução do motor ainda roda. A espera tem de descer até ela.
         */
        @Test
        @DisplayName("quandoTudoParar espera o motor que a thread do prazo deixou rodando")
        void quandoTudoPararDesceAteOMotor() {
            CompletableFuture<FlowResult> motor = new CompletableFuture<>();
            CompletableFuture<Object> thread = new CompletableFuture<>();
            CompletableFuture<Void> tudo = AgentFlowRunner.ExecucaoEmCurso.quandoTudoParar(thread);

            thread.completeExceptionally(new java.util.concurrent.CompletionException(
                    new AgentFlowRunner.PrazoDoFluxo("Falha ao executar o fluxo: TimeoutException", null, motor)));

            assertThat(tudo).as("a thread terminou, o motor não").isNotDone();
            motor.complete(resultado(ExecutionStatus.CANCELLED, null));
            assertThat(tudo).isCompleted();
        }

        @Test
        @DisplayName("timeout_ms da política: ERROR que diz o prazo, e o gasto posterior vai como APOS_PRAZO")
        void timeoutDaPoliticaRelataOQueVeioDepois() {
            passoQueTermina(800, false);

            dispatcher().runAndReport(invoke(null, Map.of("timeout_ms", 300), "manual@1"));

            assertThat(sender.sent).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
                // Antes "Falha ao executar o fluxo: TimeoutException" (RCHFLW-190): o prefixo fica.
                assertThat(r.error()).as("diz qual prazo venceu e de quanto era")
                        .isEqualTo("Falha ao executar o fluxo: não terminou em 300 ms (politica.timeout_ms)");
                assertThat(r.uso()).isNull();
            });
            esperar(() -> assertThat(sender.relatos)
                    .as("antes: nenhum relato, e o motor seguia rodando")
                    .singleElement().satisfies(rel -> {
                        assertThat(rel.motivo()).isEqualTo(VendaxUsoRelato.Motivo.APOS_PRAZO);
                        assertThat(rel.uso().tokens()).isEqualTo(1500);
                    }));
            verify(engine).cancel(anyString());
        }

        @Test
        @DisplayName("o gasto se divide no prazo: o ERROR leva o de antes, o relato só o de depois")
        void divideNoPrazo() {
            passoQueTermina(800, true);

            dispatcher().runAndReport(invoke(daquiA(300), Map.of(), "manual@1"));

            assertThat(sender.sent).singleElement()
                    .satisfies(r -> assertThat(r.uso().tokens()).isEqualTo(1100));
            esperar(() -> assertThat(sender.relatos)
                    .singleElement().satisfies(rel -> assertThat(rel.uso().tokens()).isEqualTo(1500)));
        }

        @Test
        @DisplayName("corte: o result leva o retrato do prazo mesmo que um turno termine antes de ele ser montado")
        void corteFixaODoResult() {
            ContadorDeUso uso = new ContadorDeUso();
            VendaxAgentDispatcher d = dispatcher();
            assertThat(uso.cortar()).as("nada gasto até o prazo").isEmpty();
            uso.somar("openrouter", "openrouter/m", 1000, 500);

            VendaxResult erro = d.comUsoDe(VendaxResult.error(invoke(null, Map.of(), "manual@1"), "prazo"), uso);

            assertThat(erro.uso()).as("o turno de depois é do relato, não do result").isNull();
            assertThat(uso.resumo()).get().extracting(ContadorDeUso.Resumo::tokens).isEqualTo(1500L);
        }
    }

    @Nested
    @DisplayName("RCHFLW-171: suspensão na rota em fluxo")
    class Suspensao {

        @Test
        @DisplayName("em fluxo, a suspensão leva o uso só no ERROR: nenhum relato SUSPENSO")
        void umCanalSo() {
            when(engine.execute(any(), any())).thenAnswer(chamada -> {
                ExecutionContext ctx = chamada.getArgument(1);
                ContadorDeUso.de(ctx).orElseThrow().somar("openrouter", "openrouter/m", 1000, 100);
                return CompletableFuture.completedFuture(resultado(ExecutionStatus.PAUSED, null));
            });

            var feito = dispatcher().executarEmFluxo(invoke(null, Map.of(), null),
                    SaidaDeTexto.soCancelamento(new CompletableFuture<>()), new ContadorDeUso());

            assertThat(feito.resultado().status()).isEqualTo(VendaxResult.ERROR);
            assertThat(feito.resultado().error()).contains("decisão humana");
            assertThat(feito.resultado().uso().tokens()).isEqualTo(1100);
            assertThat(sender.relatos)
                    .as("antes: /uso SUSPENSO com os mesmos 1100 tokens — o Core contava duas vezes")
                    .isEmpty();
        }

        @Test
        @DisplayName("na rota assíncrona, sem result, o gasto continua indo como SUSPENSO")
        void assincronaRelata() {
            when(engine.execute(any(), any())).thenAnswer(chamada -> {
                ExecutionContext ctx = chamada.getArgument(1);
                ContadorDeUso.de(ctx).orElseThrow().somar("openrouter", "openrouter/m", 1000, 100);
                return CompletableFuture.completedFuture(resultado(ExecutionStatus.PAUSED, null));
            });

            dispatcher().runAndReport(invoke(null, Map.of(), "manual@1"));

            assertThat(sender.sent).isEmpty();
            assertThat(sender.relatos).singleElement()
                    .satisfies(r -> assertThat(r.motivo()).isEqualTo(VendaxUsoRelato.Motivo.SUSPENSO));
        }
    }

    @Nested
    @DisplayName("RCHFLW-166: cancelamento em fluxo")
    class Cancelamento {

        /**
         * O cancelamento faz o laço desistir do turno em voo — e anotá-lo — na thread do motor,
         * enquanto a thread da invocação é interrompida e monta o resultado. Sem esperar o motor, ela
         * lia o contador antes da anotação, e o CANCELADO saía com uso nulo.
         */
        @Test
        @DisplayName("o resultado do cancelamento é montado depois de o motor anotar o turno abandonado")
        void esperaOMotor() throws Exception {
            cancelarNoMeio(null);
        }

        /**
         * O mesmo, com {@code validoAte} — o caso comum, porque o Core manda o prazo. Aí quem é
         * interrompido é a thread que espera o prazo, e ela lançava na hora, sem esperar o fluxo.
         */
        @Test
        @DisplayName("com validoAte no envelope, o cancelamento também espera o motor anotar o turno")
        void esperaOMotorComPrazo() throws Exception {
            cancelarNoMeio(daquiA(10_000));
        }

        private void cancelarNoMeio(String validoAte) throws Exception {
            when(engine.execute(any(), any())).thenAnswer(chamada -> {
                ExecutionContext ctx = chamada.getArgument(1);
                ContadorDeUso uso = ContadorDeUso.de(ctx).orElseThrow();
                SaidaDeTexto saida = SaidaDeTexto.de(ctx).orElseThrow();
                CompletableFuture<FlowResult> motor = new CompletableFuture<>();
                saida.cancelamento().thenRunAsync(() -> {
                    dormir(150);
                    uso.somarTurnoInterrompido("openrouter", "openrouter/m");
                    motor.complete(resultado(ExecutionStatus.FAILED, null));
                });
                executando.countDown();
                return motor;
            });
            CompletableFuture<Void> cancelamento = new CompletableFuture<>();
            ContadorDeUso uso = new ContadorDeUso();
            AtomicReference<VendaxAgentDispatcher.ResultadoEmFluxo> feito = new AtomicReference<>();
            VendaxAgentDispatcher d = dispatcher();
            Thread invocacao = Thread.ofVirtual().start(() -> feito.set(d.executarEmFluxo(
                    invoke(validoAte, Map.of(), null), SaidaDeTexto.soCancelamento(cancelamento), uso)));
            assertThat(executando.await(5, TimeUnit.SECONDS)).isTrue();

            // O que ExecucoesEmAndamento.cancelar faz: sinaliza e interrompe a thread da invocação.
            cancelamento.complete(null);
            invocacao.interrupt();
            invocacao.join(5_000);

            VendaxResult r = d.comUsoDe(VendaxResult.cancelado(invoke(null, Map.of(), null), "parcial"), uso);
            assertThat(feito.get().resultado().uso())
                    .as("antes: nulo — o turno abandonado era anotado depois da leitura")
                    .isNotNull()
                    .satisfies(u -> {
                        assertThat(u.llmTurns()).isEqualTo(1);
                        assertThat(u.turnsWithoutCost()).isEqualTo(1);
                        assertThat(u.tokens()).as("desconhecido, nunca zero").isNull();
                        assertThat(u.costCents()).isNull();
                    });
            assertThat(r.uso()).isNotNull();
            verify(engine).cancel(anyString());
        }

        /**
         * Cancelado na rota assíncrona, sem {@code validoAte}: a espera pelo motor tem o teto do
         * {@code timeout_ms} (200 ms) e o passo bloqueante só termina aos 700 ms. O result leva o
         * corte; o turno que o motor anota depois do teto vai como {@code APOS_PRAZO}, uma vez só.
         */
        @Test
        @DisplayName("cancelado: o que o motor gasta depois do teto da espera vai como APOS_PRAZO")
        void canceladoComOMotorPassandoDoTeto() throws Exception {
            passoQueTermina(700, true);
            VendaxAgentDispatcher d = dispatcher();

            Thread worker = Thread.ofVirtual().start(
                    () -> d.runAndReport(invoke(null, Map.of("timeout_ms", 200), "manual@1")));
            assertThat(executando.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(5_000);

            assertThat(sender.sent).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
                assertThat(r.uso().tokens()).as("o corte: só o gasto até o teto").isEqualTo(1100);
            });
            esperar(() -> assertThat(sender.relatos)
                    .as("antes: nenhum relato — o turno anotado depois do teto se perdia")
                    .singleElement().satisfies(rel -> {
                        assertThat(rel.motivo()).isEqualTo(VendaxUsoRelato.Motivo.APOS_PRAZO);
                        assertThat(rel.uso().tokens()).isEqualTo(1500);
                        assertThat(rel.uso().execucaoId()).isEqualTo(contador.get().execucaoId());
                    }));
            assertThat(sender.relatoEmThreadInterrompida).containsOnly(false);
        }

        /**
         * O mesmo com {@code validoAte}: quem é interrompido é a thread que espera o prazo, e ela
         * desiste no {@code validoAte} (600 ms), antes do passo (1200 ms).
         */
        @Test
        @DisplayName("cancelado com validoAte: o que vem depois do prazo vai como APOS_PRAZO")
        void canceladoComValidoAtePassandoDoPrazo() throws Exception {
            passoQueTermina(1_200, true);
            VendaxAgentDispatcher d = dispatcher();

            Thread worker = Thread.ofVirtual().start(
                    () -> d.runAndReport(invoke(daquiA(600), Map.of(), "manual@1")));
            assertThat(executando.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(5_000);

            assertThat(sender.sent).singleElement()
                    .satisfies(r -> assertThat(r.uso().tokens()).isEqualTo(1100));
            esperar(() -> assertThat(sender.relatos)
                    .as("antes: nenhum relato — a espera do cancelamento desistia no prazo e calava")
                    .singleElement().satisfies(rel -> {
                        assertThat(rel.motivo()).isEqualTo(VendaxUsoRelato.Motivo.APOS_PRAZO);
                        assertThat(rel.uso().tokens()).isEqualTo(1500);
                    }));
        }

        /** O motor terminou dentro do teto: tudo vai no result, e não sobra relato. */
        @Test
        @DisplayName("cancelado com o motor terminando dentro do teto: tudo no result, nenhum relato")
        void canceladoDentroDoTeto() throws Exception {
            passoQueTermina(150, true);
            VendaxAgentDispatcher d = dispatcher();

            Thread worker = Thread.ofVirtual().start(
                    () -> d.runAndReport(invoke(null, Map.of("timeout_ms", 5_000), "manual@1")));
            assertThat(executando.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(5_000);

            assertThat(sender.sent).singleElement()
                    .satisfies(r -> assertThat(r.uso().tokens()).isEqualTo(2600));
            dormir(300);
            assertThat(sender.relatos).as("nada gasto depois do corte, nada relatado").isEmpty();
        }
    }
}
