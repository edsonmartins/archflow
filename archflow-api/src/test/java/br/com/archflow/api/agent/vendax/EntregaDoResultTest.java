package br.com.archflow.api.agent.vendax;

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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A entrega do result ao Core, com reentrega (pedido do VendaX de 22/09/2026).
 *
 * <p>Medido lá: um pico de carga esgotou o pool de conexões do Core por ~3 s, 5 de 9 results
 * receberam 5xx e se perderam — o {@code send} tentava uma vez só, enquanto o relato de uso já
 * reentregava. Repetir é seguro porque o Core processa cada result pela {@code idempotencyKey}.</p>
 */
@DisplayName("entrega do result ao Core")
class EntregaDoResultTest {

    private HttpServer server;
    private String baseUrl;
    private final Queue<Integer> respostas = new ConcurrentLinkedQueue<>();
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<String> timestamps = new CopyOnWriteArrayList<>();
    private final List<String> caminhos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subir() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/public/archflow", exchange -> {
            caminhos.add(exchange.getRequestURI().getPath());
            corpos.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            timestamps.add(exchange.getRequestHeaders().getFirst("X-Archflow-Timestamp"));
            Integer status = respostas.poll();
            exchange.sendResponseHeaders(status == null ? 200 : status, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void descer() {
        server.stop(0);
    }

    /** 1 s, 3 s, 9 s viram 1, 3 e 9 ms: o teste confere a ordem e o número, não o relógio. */
    private VendaxResultSender sender(String url) {
        VendaxResultSender s = new VendaxResultSender(url, "segredo-do-webhook");
        s.setEscalaDasEsperasDoResult(1_000);
        return s;
    }

    private static VendaxResult result() {
        VendaxInvoke invoke = new VendaxInvoke("1.0", "tenant-1", "conv-1", "CS", "msg-1", null,
                "LIGHT", "sentiment_window", "trace-9", null, "cli-1", "vend-1", "cs-chave", null,
                null, null);
        return VendaxResult.ok(invoke, "sentiment", "{\"score\":-3}");
    }

    @Test
    @DisplayName("503, 503, 200: três POSTs, e o result entregue")
    void reentregaEm5xx() {
        respostas.addAll(List.of(503, 503, 200));

        sender(baseUrl).send(result(), "trace-9");

        assertThat(corpos).hasSize(3).allSatisfy(c -> assertThat(c).isEqualTo(corpos.get(0)));
        assertThat(caminhos).containsOnly("/api/v1/public/archflow/result");
        assertThat(respostas).isEmpty();
    }

    /** O controle do pedido: 4xx não passa a caber na tentativa seguinte. */
    @Test
    @DisplayName("400: um POST só")
    void naoReentregaEm4xx() {
        respostas.addAll(List.of(400, 200));

        sender(baseUrl).send(result(), "trace-9");

        assertThat(corpos).hasSize(1);
    }

    @Test
    @DisplayName("401 (assinatura): um POST só")
    void naoReentregaEm401() {
        respostas.add(401);

        sender(baseUrl).send(result(), "trace-9");

        assertThat(corpos).hasSize(1);
    }

    /** A primeira e mais três: com o Core fora, desiste — e não segura a thread para sempre. */
    @Test
    @DisplayName("5xx em todas: quatro POSTs e desiste, sem lançar")
    void desisteDepoisDeQuatro() {
        respostas.addAll(List.of(500, 502, 503, 504, 200));

        sender(baseUrl).send(result(), "trace-9");

        assertThat(corpos).hasSize(1 + VendaxResultSender.ESPERAS_DO_RESULT.length).hasSize(4);
        assertThat(respostas).containsExactly(200);
    }

    @Test
    @DisplayName("falha de rede: tenta de novo, e desiste sem lançar")
    void falhaDeRede() {
        String semNinguem = "http://127.0.0.1:" + (server.getAddress().getPort());
        server.stop(0);

        sender(semNinguem).send(result(), "trace-9");

        assertThat(corpos).isEmpty();
    }

    /** O timestamp entra na assinatura: um reenvio com o de antes cairia na janela do Core. */
    @Test
    @DisplayName("cada tentativa sai assinada de novo")
    void assinaDeNovo() {
        respostas.addAll(List.of(503, 200));

        sender(baseUrl).send(result(), "trace-9");

        assertThat(timestamps).hasSize(2).doesNotContainNull();
    }

    @Test
    @DisplayName("as esperas crescem: 1 s, 3 s, 9 s")
    void esperasCrescentes() {
        assertThat(VendaxResultSender.ESPERAS_DO_RESULT)
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(9));
    }

    @Test
    @DisplayName("o send de um argumento — o de sempre — também reentrega")
    void semTraceId() {
        respostas.addAll(List.of(503, 200));

        sender(baseUrl).send(result());

        assertThat(corpos).hasSize(2);
    }
}
