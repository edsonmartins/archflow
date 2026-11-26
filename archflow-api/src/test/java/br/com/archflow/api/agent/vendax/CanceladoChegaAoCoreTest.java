package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.agent.stream.ExecucoesEmAndamento;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O {@code CANCELADO} da rota assíncrona chega ao Core (RCHFLW-165), e liberar a chave antes do
 * envio não tira do registro quem não é dono dela.
 *
 * <p>O {@code ExecucaoEmFluxoTest} já provava que o result do cancelamento é montado, mas com o sender
 * simulado — e o defeito estava justamente no envio: o cancelamento interrompe a thread, o
 * {@code AgentFlowRunner} restaura a flag ao sair, e o {@code http.send} do sender desistia na hora
 * ({@code INTERROMPIDA}, sem nova tentativa). Aqui o sender é o real, contra um receptor local, e o
 * dublê do fluxo faz o que o runner faz: anota o turno, espera, é interrompido, restaura a flag e
 * lança.</p>
 */
@DisplayName("cancelamento da rota assíncrona entrega o CANCELADO ao Core")
class CanceladoChegaAoCoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> DOCUMENTO = Map.of(
            "id", "qa2", "steps", List.of(Map.of("id", "agente", "type", "mcp-agent")));

    /** Um caminho do receptor: o que ele responde e o que ele recebeu. */
    private static final class Receptor {
        /** Status que o receptor devolve, em ordem; vazio = 200. */
        final Queue<Integer> respostas = new ConcurrentLinkedQueue<>();
        /** Só os corpos que o receptor aceitou (2xx): é o que o Core de fato processou. */
        final List<String> aceitos = new CopyOnWriteArrayList<>();
        final List<String> recebidos = new CopyOnWriteArrayList<>();
        /** A primeira requisição chegou (e, se {@link #segurar}, está parada). */
        final CountDownLatch chegou = new CountDownLatch(1);
        /** Segura a primeira requisição até o teste soltar — o envio "em voo". */
        volatile boolean segurar;
        final CountDownLatch soltar = new CountDownLatch(1);

        void atender(HttpExchange exchange) throws IOException {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean primeira = recebidos.isEmpty();
            recebidos.add(corpo);
            chegou.countDown();
            if (primeira && segurar) {
                try {
                    soltar.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Integer status = respostas.poll();
            int codigo = status == null ? 200 : status;
            if (codigo / 100 == 2) {
                aceitos.add(corpo);
            }
            exchange.sendResponseHeaders(codigo, -1);
            exchange.close();
        }
    }

    private HttpServer server;
    private String baseUrl;
    private final Receptor result = new Receptor();
    private final Receptor relatos = new Receptor();
    private ExecutorService executor;

    @BeforeEach
    void subir() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/public/archflow/result", result::atender);
        server.createContext("/api/v1/public/archflow/uso", relatos::atender);
        // Um handler segurado não pode travar o outro caminho.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void descer() {
        result.soltar.countDown();
        relatos.soltar.countDown();
        executor.shutdownNow();
        server.stop(0);
    }

    private static VendaxInvoke invoke(String chave) {
        return new VendaxInvoke("1.0", "t1", "conv-1", "QA2", "m1", "texto longo", "LIGHT", "teste",
                "trace-1", null, null, null, chave, null,
                new DefinicaoDeAgente("FLUXO", DOCUMENTO, null, "manual@1",
                        List.of(), Map.of(), Map.of(), "qa2@1"));
    }

    private VendaxResultSender sender() {
        VendaxResultSender sender = new VendaxResultSender(baseUrl, "segredo-sintetico");
        sender.setEscalaDasEsperasDoResult(1_000);
        sender.setEsperaEntreTentativas(Duration.ofMillis(10));
        return sender;
    }

    private VendaxAgentDispatcher dispatcher(AgentFlowRunner fluxo, ExecucoesEmAndamento execucoes) {
        VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(mock(QpAgentService.class),
                mock(McpAgentRunner.class), mock(VendaxMcpClientProvider.class), sender(), executor, null,
                fluxo);
        dispatcher.setExecucoes(execucoes);
        return dispatcher;
    }

    private static void esperarAte(java.util.function.BooleanSupplier condicao) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicao.getAsBoolean() && System.nanoTime() < limite) {
            Thread.sleep(10);
        }
    }

    private void terminarOPool() throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("cancel por chave: o receptor aceita um ERROR CANCELADO com o uso até ali, mesmo depois de um 503")
    void canceladoEhEntregue() throws Exception {
        // O 503 primeiro torna o defeito determinístico: com a flag ligada, a primeira tentativa já
        // desistia (ou, se chegava a sair, não havia segunda) — nenhum corpo era aceito.
        result.respostas.add(503);
        AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
        CountDownLatch dentro = new CountDownLatch(1);
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            // Um turno que já gastou antes do cancelamento: é ele que o CANCELADO tem de levar.
            ContadorDeUso uso = inv.getArgument(3);
            uso.somar("openrouter", "openrouter/m", 1000, 500);
            dentro.countDown();
            try {
                // A chamada de modelo em voo: só sai quando o cancelamento interrompe a thread.
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                throw new AssertionError("o cancelamento não interrompeu a execução");
            } catch (InterruptedException e) {
                // O que o AgentFlowRunner faz: restaura a flag e lança.
                Thread.currentThread().interrupt();
                throw new AgentFlowRunner.ExecucaoEmCurso("Execução do fluxo interrompida", e, null);
            }
        });
        ExecucoesEmAndamento execucoes = new ExecucoesEmAndamento();
        VendaxAgentDispatcher dispatcher = dispatcher(fluxo, execucoes);

        dispatcher.dispatch(invoke("qa2-acancel-1"));
        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(execucoes.cancelar("qa2-acancel-1")).isTrue();

        esperarAte(() -> !result.aceitos.isEmpty());
        assertThat(result.aceitos).as("antes: nenhum webhook (recebidos=%s)", result.recebidos).hasSize(1);
        JsonNode r = JSON.readTree(result.aceitos.get(0));
        assertThat(r.path("status").asText()).isEqualTo(VendaxResult.ERROR);
        assertThat(r.path("encerradoPor").path("name").asText()).isEqualTo(VendaxResult.CANCELADO);
        assertThat(r.path("idempotencyKey").asText()).isEqualTo("qa2-acancel-1");
        // O uso até ali vai no CANCELADO (docs/streaming-invoke.md), e só nele: nenhum relato à parte.
        assertThat(r.path("uso").isObject()).as("CANCELADO sem uso: %s", r).isTrue();
        assertThat(r.path("uso").path("tokens").asLong()).isEqualTo(1500);
        assertThat(r.path("uso").path("llmTurns").asInt()).isEqualTo(1);
        assertThat(relatos.recebidos).isEmpty();
        assertThat(execucoes.emAndamento("qa2-acancel-1")).isFalse();
        // Um segundo cancel já não acha a chave: a execução saiu do registro antes do envio.
        assertThat(execucoes.cancelar("qa2-acancel-1")).isFalse();
    }

    @Test
    @DisplayName("execução nova da mesma chave, admitida durante o webhook, continua no registro e cancelável")
    void novaExecucaoDaMesmaChaveNaoSaiDoRegistro() throws Exception {
        // O webhook fica em voo até o teste soltar — o caso real é o 5xx com as esperas de 1+3+9 s.
        result.segurar = true;
        AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AgentFlowRunner.Saida("{\"a\":1}", false));
        ExecucoesEmAndamento execucoes = new ExecucoesEmAndamento();
        dispatcher(fluxo, execucoes).dispatch(invoke("k1"));
        assertThat(result.chegou.await(5, TimeUnit.SECONDS)).isTrue();

        // A chave já saiu do registro (enviar a libera antes do envio), e outra execução dela — a
        // próxima mensagem, ou a rota em fluxo — é admitida enquanto o webhook ainda sai.
        var nova = execucoes.registrar("k1", "t1", null);
        assertThat(nova).isPresent();
        result.soltar.countDown();
        terminarOPool();

        // Antes: o `finally` concluía pela chave e tirava a nova do registro — cancel 404, a conexão
        // que cai não cancelava, e uma terceira chamada não levava 409.
        assertThat(execucoes.emAndamento("k1")).as("a nova execução continua no registro").isTrue();
        assertThat(execucoes.registrar("k1", "t1", null)).as("a terceira chamada leva 409").isEmpty();
        assertThat(execucoes.cancelar(nova.get())).as("a nova continua cancelável").isTrue();
        assertThat(result.aceitos).hasSize(1);
    }

    @Test
    @DisplayName("fluxo suspenso: um cancel durante o POST /uso não derruba o relato SUSPENSO")
    void relatoSuspensoNaoCaiComOCancelamento() throws Exception {
        // A primeira tentativa do relato fica em voo e volta 503: só uma thread que não foi
        // interrompida faz a segunda, e é a segunda que o Core aceita.
        relatos.segurar = true;
        relatos.respostas.add(503);
        AgentFlowRunner fluxo = mock(AgentFlowRunner.class);
        when(fluxo.executar(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            ContadorDeUso uso = inv.getArgument(3);
            uso.somar("openrouter", "openrouter/m", 1000, 100);
            return new AgentFlowRunner.Saida(null, true);
        });
        ExecucoesEmAndamento execucoes = new ExecucoesEmAndamento();
        dispatcher(fluxo, execucoes).dispatch(invoke("qa2-susp-1"));
        assertThat(relatos.chegou.await(5, TimeUnit.SECONDS)).isTrue();

        // Antes: a chave seguia registrada durante o relato; o cancel respondia 202 e interrompia o
        // HTTP — INTERROMPIDA, só um WARN, e o gasto não chegava ao Core.
        boolean cancelou = execucoes.cancelar("qa2-susp-1");
        relatos.soltar.countDown();
        terminarOPool();

        assertThat(relatos.aceitos).as("relato SUSPENSO perdido (recebidos=%s)", relatos.recebidos)
                .hasSize(1);
        assertThat(cancelou).as("a execução já tinha terminado: o cancel não a acha (404)").isFalse();
        JsonNode relato = JSON.readTree(relatos.aceitos.get(0));
        assertThat(relato.path("motivo").asText()).isEqualTo("SUSPENSO");
        assertThat(relato.path("uso").path("tokens").asLong()).isEqualTo(1100);
        assertThat(result.recebidos).as("suspensão assíncrona não manda result").isEmpty();
    }
}
