package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.SaidaDeTexto;
import br.com.archflow.api.agent.vendax.DefinicaoDeAgente;
import br.com.archflow.api.agent.vendax.VendaxAgentDispatcher;
import br.com.archflow.api.agent.vendax.VendaxAgentDispatcher.ResultadoEmFluxo;
import br.com.archflow.api.agent.vendax.VendaxInvoke;
import br.com.archflow.api.agent.vendax.VendaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A invocação com resposta em fluxo, com o dispatcher simulado: o que se prova aqui é a entrega
 * (fim na conexão ou webhook, nunca os dois), a idempotência e o cancelamento — não o modelo.
 *
 * <p>Nenhum teste nomeia um agente ou uma aplicação: o fluxo é neutro.</p>
 */
@DisplayName("invocação com resposta em fluxo")
class InvocacaoEmFluxoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Evento(String nome, JsonNode dados) {
    }

    /** Um emitter que anota o que sai — e que pode simular a conexão caindo. */
    static final class Conexao extends SseEmitter {
        final List<String> brutos = new CopyOnWriteArrayList<>();
        volatile boolean caiu;
        volatile boolean encerrada;

        Conexao() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (caiu) {
                throw new IOException("Broken pipe");
            }
            StringBuilder sb = new StringBuilder();
            for (DataWithMediaType parte : builder.build()) {
                sb.append(parte.getData());
            }
            brutos.add(sb.toString());
        }

        @Override
        public synchronized void complete() {
            encerrada = true;
            super.complete();
        }

        List<Evento> eventos() throws IOException {
            List<Evento> lista = new java.util.ArrayList<>();
            for (String bruto : brutos) {
                if (bruto.startsWith("event:")) {
                    int dados = bruto.indexOf("\ndata:");
                    lista.add(new Evento(bruto.substring(6, dados),
                            JSON.readTree(bruto.substring(dados + 6).trim())));
                }
            }
            return lista;
        }

        List<String> nomes() throws IOException {
            return eventos().stream().map(Evento::nome).toList();
        }

        String textoDosDeltas() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (Evento e : eventos()) {
                if (e.nome().equals("delta")) {
                    sb.append(e.dados().get("texto").asText());
                }
            }
            return sb.toString();
        }
    }

    private final VendaxAgentDispatcher dispatcher = mock(VendaxAgentDispatcher.class);
    private final ExecucoesEmAndamento execucoes = new ExecucoesEmAndamento();
    private final ResultadoGuardado guardado = new ResultadoGuardadoEmMemoria(Duration.ofMinutes(10));
    private final List<Conexao> conexoes = new CopyOnWriteArrayList<>();
    private InvocacaoEmFluxo invocacao = nova(10);

    private InvocacaoEmFluxo nova(int max) {
        return nova(max, Duration.ofSeconds(15));
    }

    private InvocacaoEmFluxo nova(int max, Duration batimento) {
        return new InvocacaoEmFluxo(dispatcher, execucoes, guardado, max, batimento, 5,
                () -> {
                    Conexao c = new Conexao();
                    conexoes.add(c);
                    return c;
                });
    }

    @AfterEach
    void fecha() {
        invocacao.close();
    }

    private static VendaxInvoke invoke(String chave, String saidaSchema) {
        return new VendaxInvoke("1.0", "t1", "c1", "assistente", "m1", "olá", "LIGHT", "teste",
                "trace-1", null, null, null, chave, null,
                new DefinicaoDeAgente("FLUXO", Map.of("steps", List.of()), null, saidaSchema,
                        List.of(), Map.of(), Map.of(), "demo@1"));
    }

    private static VendaxResult ok(VendaxInvoke invoke, String texto) {
        return VendaxResult.ok(invoke, VendaxResult.TEXTO, texto);
    }

    /** O dispatcher "gera" os pedaços pelo sink, como o laço faria, e devolve o resultado. */
    private void simulaModelo(String... pedacos) {
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            VendaxInvoke invoke = inv.getArgument(0);
            SaidaDeTexto saida = inv.getArgument(1);
            for (String p : pedacos) {
                saida.delta(p);
            }
            return new ResultadoEmFluxo(ok(invoke, String.join("", pedacos)), false);
        });
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Conexao aceita(VendaxInvoke invoke) {
        var admissao = invocacao.iniciar(invoke);
        assertThat(admissao).isInstanceOf(InvocacaoEmFluxo.Admissao.Aceita.class);
        return conexoes.get(conexoes.size() - 1);
    }

    private static void espera(java.util.concurrent.Callable<Boolean> condicao) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condicao.call()) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("condição não chegou em 5 s");
            }
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("fim entregue na conexão: nenhum webhook, e os deltas somam o texto do resultado")
    void fimEntregueSemWebhook() throws Exception {
        simulaModelo("Olá", ", ", "mundo");

        Conexao c = aceita(invoke("k1", null));
        espera(() -> c.encerrada);

        assertThat(c.nomes()).containsExactly("inicio", "delta", "delta", "delta", "fim");
        assertThat(c.eventos().get(0).dados().get("idempotencyKey").asText()).isEqualTo("k1");
        assertThat(c.eventos().get(0).dados().get("traceId").asText()).isEqualTo("trace-1");
        // seq cresce a partir de 0, sem buracos
        List<Evento> eventos = c.eventos();
        for (int i = 0; i < eventos.size(); i++) {
            assertThat(eventos.get(i).dados().get("seq").asInt()).isEqualTo(i);
        }
        JsonNode fim = eventos.get(eventos.size() - 1).dados();
        assertThat(fim.get("status").asText()).isEqualTo("OK");
        assertThat(fim.get("richObjectType").asText()).isEqualTo("text");
        assertThat(c.textoDosDeltas()).isEqualTo(fim.get("richObject").asText());
        verify(dispatcher, never()).entregarPorWebhook(any(), any());
    }

    @Test
    @DisplayName("conexão derrubada antes do fim: o webhook chega com o mesmo resultado e a mesma chave")
    void quedaEntregaPorWebhook() throws Exception {
        CountDownLatch deltaEntregue = new CountDownLatch(1);
        CountDownLatch podeTerminar = new CountDownLatch(1);
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            VendaxInvoke invoke = inv.getArgument(0);
            SaidaDeTexto saida = inv.getArgument(1);
            saida.delta("parte ");
            deltaEntregue.countDown();
            podeTerminar.await(5, TimeUnit.SECONDS);
            return new ResultadoEmFluxo(ok(invoke, "parte final"), false);
        });

        Conexao c = aceita(invoke("k2", null));
        assertThat(deltaEntregue.await(5, TimeUnit.SECONDS)).isTrue();
        c.caiu = true;
        podeTerminar.countDown();

        var resultado = org.mockito.ArgumentCaptor.forClass(VendaxResult.class);
        verify(dispatcher, timeout(5000)).entregarPorWebhook(resultado.capture(), any());
        assertThat(resultado.getValue().idempotencyKey()).isEqualTo("k2");
        assertThat(resultado.getValue().status()).isEqualTo("OK");
        assertThat(resultado.getValue().richObject())
                .as("o mesmo resultado de texto que iria no fim")
                .isEqualTo("parte final");
        assertThat(c.nomes()).doesNotContain("fim");
    }

    @Test
    @DisplayName("chave em andamento devolve 409; terminada devolve só o fim guardado, sem chamar o modelo")
    void idempotencia() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch libera = new CountDownLatch(1);
        AtomicInteger chamadas = new AtomicInteger();
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            chamadas.incrementAndGet();
            VendaxInvoke invoke = inv.getArgument(0);
            dentro.countDown();
            libera.await(5, TimeUnit.SECONDS);
            ((SaidaDeTexto) inv.getArgument(1)).delta("pronto");
            return new ResultadoEmFluxo(ok(invoke, "pronto"), false);
        });

        Conexao primeira = aceita(invoke("k3", null));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

        var repetida = invocacao.iniciar(invoke("k3", null));
        assertThat(repetida).isInstanceOfSatisfying(InvocacaoEmFluxo.Admissao.Recusa.class,
                r -> assertThat(r.status()).isEqualTo(409));

        libera.countDown();
        espera(() -> primeira.encerrada);

        Conexao segunda = aceita(invoke("k3", null));
        assertThat(segunda.nomes()).as("só o fim, sem inicio nem delta").containsExactly("fim");
        assertThat(segunda.eventos().get(0).dados().get("richObject").asText()).isEqualTo("pronto");
        assertThat(chamadas.get()).as("o modelo não foi chamado de novo").isEqualTo(1);
    }

    @Test
    @DisplayName("a chave guardada não vale para outro tenant")
    void outroTenantNaoLeOResultado() throws Exception {
        simulaModelo("segredo do tenant t1");
        Conexao c = aceita(invoke("k4", null));
        espera(() -> c.encerrada);

        VendaxInvoke deOutro = new VendaxInvoke("1.0", "t2", "c1", "assistente", "m1", "olá",
                "LIGHT", "teste", "trace", null, null, null, "k4", null,
                invoke("k4", null).definicao());

        assertThat(invocacao.iniciar(deOutro)).isInstanceOfSatisfying(
                InvocacaoEmFluxo.Admissao.Recusa.class, r -> assertThat(r.status()).isEqualTo(409));
    }

    @Test
    @DisplayName("fluxo com saída estruturada: o sink não transmite e nenhum delta sai")
    void saidaEstruturadaNaoEmiteDelta() throws Exception {
        List<Boolean> transmitia = new CopyOnWriteArrayList<>();
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            VendaxInvoke invoke = inv.getArgument(0);
            SaidaDeTexto saida = inv.getArgument(1);
            transmitia.add(saida.transmite());
            saida.tool("consulta", true, null);
            saida.tool("consulta", false, 12L);
            return new ResultadoEmFluxo(
                    VendaxResult.ok(invoke, "demo", "{\"a\":1}"), false);
        });

        Conexao c = aceita(invoke("k5", "demo@1"));
        espera(() -> c.encerrada);

        assertThat(transmitia).containsExactly(false);
        assertThat(c.nomes()).containsExactly("inicio", "tool", "tool", "fim");
        assertThat(c.eventos().get(1).dados().get("fase").asText()).isEqualTo("INICIO");
        assertThat(c.eventos().get(2).dados().get("fase").asText()).isEqualTo("FIM");
        assertThat(c.eventos().get(2).dados().get("duracaoMs").asLong()).isEqualTo(12);
    }

    @Test
    @DisplayName("provedor sem streaming: um delta com o texto inteiro, e depois o fim")
    void semStreamingUmDelta() throws Exception {
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv ->
                // o laço não emitiu nada: o provedor não transmite
                new ResultadoEmFluxo(ok(inv.getArgument(0), "texto inteiro"), false));

        Conexao c = aceita(invoke("k6", null));
        espera(() -> c.encerrada);

        assertThat(c.nomes()).containsExactly("inicio", "delta", "fim");
        assertThat(c.textoDosDeltas()).isEqualTo("texto inteiro");
    }

    @Test
    @DisplayName("acima do limite de execuções simultâneas: 429 com Retry-After")
    void limiteDeCapacidade() throws Exception {
        invocacao.close();
        invocacao = nova(1);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch libera = new CountDownLatch(1);
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            dentro.countDown();
            libera.await(5, TimeUnit.SECONDS);
            return new ResultadoEmFluxo(ok(inv.getArgument(0), "x"), false);
        });

        aceita(invoke("k7", null));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(invocacao.iniciar(invoke("k8", null))).isInstanceOfSatisfying(
                InvocacaoEmFluxo.Admissao.Recusa.class, r -> {
                    assertThat(r.status()).isEqualTo(429);
                    assertThat(r.retryAfterSegundos()).isEqualTo(5);
                });
        libera.countDown();
        espera(() -> invocacao.ativas() == 0);
        assertThat(invocacao.iniciar(invoke("k9", null)))
                .as("a vaga volta quando a execução termina")
                .isInstanceOf(InvocacaoEmFluxo.Admissao.Aceita.class);
    }

    @Test
    @DisplayName("cancelar por chave com a conexão aberta: fim CANCELADO na conexão, texto parcial e sem webhook")
    void cancelarPorChave() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            VendaxInvoke invoke = inv.getArgument(0);
            SaidaDeTexto saida = inv.getArgument(1);
            saida.delta("começo");
            dentro.countDown();
            try {
                saida.cancelamento().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // cancelado ou interrompido: o fluxo falha, como o motor faria
            }
            return new ResultadoEmFluxo(VendaxResult.error(invoke, "Fluxo falhou"), true);
        });

        Conexao c = aceita(invoke("k10", null));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(invocacao.cancelar("k10")).isTrue();
        espera(() -> c.encerrada);

        List<Evento> eventos = c.eventos();
        JsonNode fim = eventos.get(eventos.size() - 1).dados();
        assertThat(eventos.get(eventos.size() - 1).nome()).isEqualTo("fim");
        assertThat(fim.get("encerradoPor").get("name").asText()).isEqualTo("CANCELADO");
        assertThat(fim.get("richObject").asText()).isEqualTo("começo");
        verify(dispatcher, never()).entregarPorWebhook(any(), any());
        assertThat(invocacao.cancelar("k10")).as("terminou: não há mais o que cancelar").isFalse();
    }

    @Test
    @DisplayName("fechar a conexão cancela a execução, e o resultado CANCELADO vai pelo webhook")
    void fecharAConexaoCancela() throws Exception {
        // Batimento curto: é a escrita do batimento que revela a conexão morta enquanto o modelo
        // está calado.
        invocacao.close();
        invocacao = nova(10, Duration.ofMillis(200));
        CountDownLatch dentro = new CountDownLatch(1);
        CompletableFuture<Void> viuCancelamento = new CompletableFuture<>();
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv -> {
            VendaxInvoke invoke = inv.getArgument(0);
            SaidaDeTexto saida = inv.getArgument(1);
            dentro.countDown();
            try {
                saida.cancelamento().get(5, TimeUnit.SECONDS);
                viuCancelamento.complete(null);
            } catch (InterruptedException e) {
                // o cancelamento também interrompe a thread da execução — é o que tira o fluxo
                // de uma espera bloqueante
                viuCancelamento.complete(null);
            } catch (Exception e) {
                viuCancelamento.completeExceptionally(e);
            }
            return new ResultadoEmFluxo(VendaxResult.error(invoke, "Fluxo falhou"), true);
        });

        Conexao c = aceita(invoke("k11", null));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();
        c.caiu = true;

        viuCancelamento.get(2, TimeUnit.SECONDS);
        var resultado = org.mockito.ArgumentCaptor.forClass(VendaxResult.class);
        verify(dispatcher, timeout(5000)).entregarPorWebhook(resultado.capture(), any());
        assertThat(resultado.getValue().encerradoPor().name()).isEqualTo("CANCELADO");
        assertThat(resultado.getValue().idempotencyKey()).isEqualTo("k11");
    }

    @Test
    @DisplayName("falha do fluxo: evento erro, conexão encerrada e o resultado de erro pelo webhook")
    void falhaViraErroEWebhook() throws Exception {
        when(dispatcher.comUsoDe(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.executarEmFluxo(any(), any(), any())).thenAnswer(inv ->
                new ResultadoEmFluxo(VendaxResult.error(inv.getArgument(0), "provedor caiu"), true));

        Conexao c = aceita(invoke("k12", null));
        espera(() -> c.encerrada);

        assertThat(c.nomes()).containsExactly("inicio", "erro");
        assertThat(c.eventos().get(1).dados().get("error").asText()).isEqualTo("provedor caiu");
        assertThat(c.eventos().get(1).dados().get("recuperavel").asBoolean()).isTrue();
        verify(dispatcher, timeout(5000).times(1)).entregarPorWebhook(any(), any());
        assertThat(guardado.buscar("k12")).as("falha não é guardada: repetir a chave tenta de novo")
                .isEmpty();
    }

    @Test
    @DisplayName("a chave sai de em andamento quando a execução termina")
    void chaveLiberada() throws Exception {
        simulaModelo("ok");
        Conexao c = aceita(invoke("k13", null));
        espera(() -> c.encerrada);
        assertThat(execucoes.emAndamento("k13")).isFalse();
        verify(dispatcher, times(1)).executarEmFluxo(any(), any(), any());
        verify(dispatcher, never()).entregarPorWebhook(any(), eq(invoke("k13", null)));
    }
}
