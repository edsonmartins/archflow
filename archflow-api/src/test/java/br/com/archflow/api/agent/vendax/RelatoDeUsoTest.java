package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /api/v1/public/archflow/uso}: o consumo que não vai num result.
 *
 * <p>O Core conta cada (execução, motivo) uma vez, então reentregar é seguro — e necessário em
 * falha dele. Reentregar um 400 não é: o relato nunca vai caber.</p>
 */
@DisplayName("relato de uso ao Core")
class RelatoDeUsoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEGREDO = "segredo-do-webhook";

    private HttpServer server;
    private String baseUrl;
    private final Queue<Integer> respostas = new ConcurrentLinkedQueue<>();
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<String> assinaturas = new CopyOnWriteArrayList<>();
    private final List<String> timestamps = new CopyOnWriteArrayList<>();
    private final List<String> caminhos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subir() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/public/archflow", exchange -> {
            caminhos.add(exchange.getRequestURI().getPath());
            corpos.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assinaturas.add(exchange.getRequestHeaders().getFirst("X-Archflow-Signature"));
            timestamps.add(exchange.getRequestHeaders().getFirst("X-Archflow-Timestamp"));
            Integer status = respostas.poll();
            int codigo = status == null ? 200 : status;
            exchange.sendResponseHeaders(codigo, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void descer() {
        server.stop(0);
    }

    private VendaxResultSender sender() {
        VendaxResultSender s = new VendaxResultSender(baseUrl, SEGREDO);
        s.setEsperaEntreTentativas(Duration.ZERO);
        return s;
    }

    private static VendaxInvoke invoke() {
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", "QP", "msg-1", "bom dia", "STRONG",
                "teste", "trace-1", null, "cli-1", "vend-1", "qp-chave", null, null, null);
    }

    private static VendaxUsoRelato relato() {
        return VendaxUsoRelato.de(invoke(), VendaxUsoRelato.Motivo.SEM_RESULT,
                new VendaxResult.Uso("openrouter/m", 5000L, 7L, "exec-1", "openrouter",
                        4600L, 400L, 3, 2, 8200L));
    }

    private static String hmac(String timestamp, String corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + corpo).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("vai para /uso, com a mesma assinatura do result e a forma do contrato")
    void formaEAssinatura() throws Exception {
        sender().relatarUso(relato());

        assertThat(caminhos).containsExactly("/api/v1/public/archflow/uso");
        assertThat(assinaturas.get(0)).isEqualTo(hmac(timestamps.get(0), corpos.get(0)));

        JsonNode corpo = MAPPER.readTree(corpos.get(0));
        assertThat(corpo.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(corpo.path("tenantId").asText()).isEqualTo("tenant-1");
        assertThat(corpo.path("agent").asText()).isEqualTo("QP");
        assertThat(corpo.path("conversationId").asText()).isEqualTo("conv-1");
        assertThat(corpo.path("idempotencyKey").asText())
                .as("a mesma chave que o result desta execução teria")
                .isEqualTo("qp-chave");
        assertThat(corpo.path("motivo").asText()).isEqualTo("SEM_RESULT");
        JsonNode uso = corpo.path("uso");
        assertThat(uso.path("execucaoId").asText()).isEqualTo("exec-1");
        assertThat(uso.path("model").asText()).isEqualTo("openrouter/m");
        assertThat(uso.path("tokens").asLong()).isEqualTo(5000);
        assertThat(uso.path("costCents").asLong()).isEqualTo(7);
        assertThat(uso.path("provider").asText()).isEqualTo("openrouter");
        assertThat(uso.path("inputTokens").asLong()).isEqualTo(4600);
        assertThat(uso.path("outputTokens").asLong()).isEqualTo(400);
        assertThat(uso.path("llmTurns").asInt()).isEqualTo(3);
        assertThat(uso.path("toolCalls").asInt()).isEqualTo(2);
        assertThat(uso.path("durationMs").asLong()).isEqualTo(8200);
    }

    @Test
    @DisplayName("5xx do Core: reentrega")
    void reentregaEm5xx() {
        respostas.add(503);
        respostas.add(200);

        sender().relatarUso(relato());

        assertThat(corpos).hasSize(2);
        assertThat(corpos.get(1)).isEqualTo(corpos.get(0));
    }

    @Test
    @DisplayName("5xx persistente: desiste depois das tentativas, sem lançar")
    void desiste() {
        respostas.add(500);
        respostas.add(500);
        respostas.add(500);
        respostas.add(500);

        sender().relatarUso(relato());

        assertThat(corpos).hasSize(VendaxResultSender.TENTATIVAS_DO_RELATO);
    }

    /** 400: motivo desconhecido, sem execucaoId — nunca vai caber. */
    @Test
    @DisplayName("400: não reentrega")
    void naoReentregaEm400() {
        respostas.add(400);

        sender().relatarUso(relato());

        assertThat(corpos).hasSize(1);
    }

    /** 401: assinatura errada — repetir não conserta. */
    @Test
    @DisplayName("401: não reentrega")
    void naoReentregaEm401() {
        respostas.add(401);

        sender().relatarUso(relato());

        assertThat(corpos).hasSize(1);
    }

    @Test
    @DisplayName("Core fora do ar: tenta e desiste, sem lançar")
    void foraDoAr() {
        server.stop(0);

        sender().relatarUso(relato());

        assertThat(corpos).isEmpty();
    }

    @Test
    @DisplayName("sem base-url, descarta sem lançar")
    void semConfiguracao() {
        new VendaxResultSender("", SEGREDO).relatarUso(relato());

        assertThat(corpos).isEmpty();
    }

    /** Sem execucaoId o Core não protege contra contagem dupla; o relato nem é montado. */
    @Test
    @DisplayName("relato sem execucaoId não existe")
    void semExecucaoId() {
        assertThatThrownBy(() -> VendaxUsoRelato.de(invoke(), VendaxUsoRelato.Motivo.SEM_RESULT,
                new VendaxResult.Uso("m", 1L, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("execucaoId");
    }

    @Test
    @DisplayName("o result continua em /result, e o uso dele traz o detalhamento")
    void resultSegueIgual() throws Exception {
        VendaxResult r = VendaxResult.ok(invoke(), "quote", "{}")
                .comUso(new VendaxResult.Uso("openrouter/m", 5000L, null, "exec-1", "openrouter",
                        4600L, 400L, 3, null, 8200L));

        sender().send(r);

        assertThat(caminhos).containsExactly("/api/v1/public/archflow/result");
        JsonNode uso = MAPPER.readTree(corpos.get(0)).path("uso");
        assertThat(uso.path("execucaoId").asText()).isEqualTo("exec-1");
        assertThat(uso.has("costCents")).as("nulo explícito, como no PR #51").isTrue();
        assertThat(uso.has("toolCalls")).as("o que não se sabe fica ausente").isFalse();
    }
}
