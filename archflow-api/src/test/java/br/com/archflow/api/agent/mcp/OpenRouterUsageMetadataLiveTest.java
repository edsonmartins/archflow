package br.com.archflow.api.agent.mcp;

import br.com.archflow.api.agent.vendax.VendaxResult;
import br.com.archflow.api.agent.vendax.VendaxResultSender;
import br.com.archflow.api.agent.vendax.VendaxUsoRelato;
import br.com.archflow.langchain4j.provider.LLMProvider;
import br.com.archflow.langchain4j.provider.LLMProviderConfig;
import br.com.archflow.langchain4j.provider.LLMProviderHub;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt-in paid smoke test; never runs in ordinary builds. */
class OpenRouterUsageMetadataLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "openrouter.live", matches = "true")
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    @DisplayName("[live opt-in] captures actual OpenRouter model, endpoint and cost")
    void capturesAndSendsProviderResponseMetadata() throws Exception {
        LLMProviderHub.reset();
        LLMProviderHub hub = LLMProviderHub.getInstance();
        hub.registerConfig("openrouter-live-usage", LLMProviderConfig.builder(LLMProvider.OPENROUTER)
                .apiKey(System.getenv("OPENROUTER_API_KEY"))
                .modelId("google/gemini-2.5-flash-lite")
                .maxTokens(24)
                .build());

        var response = hub.getModel("openrouter-live-usage", false).chat(ChatRequest.builder()
                .messages(UserMessage.from("Reply with exactly: OK"))
                .build());
        var counter = new ContadorDeUso();
        var options = new McpAgentRunner.Options(null, null, null, 1, null, null,
                null, null, Set.of(), counter);
        McpAgentRunner.somarUso(options, "openrouter", "openrouter/google/gemini-2.5-flash-lite", response);
        var summary = counter.resumo().orElseThrow();
        var callbackUsage = VendaxResult.Uso.de(summary, null);
        var turn = callbackUsage.modelUsage().getFirst();
        var tokens = response.tokenUsage();

        assertThat(turn.effectiveModel()).isNotBlank();
        assertThat(turn.effectiveProvider()).isNotBlank();
        assertThat(turn.declaredCostUsd()).isNotNull().isFinite().isGreaterThanOrEqualTo(0.0);

        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/api/v1/public/archflow/uso", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            timestamp.set(exchange.getRequestHeaders().getFirst("X-Archflow-Timestamp"));
            signature.set(exchange.getRequestHeaders().getFirst("X-Archflow-Signature"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        String hmacSecret = "live-smoke-secret";
        try {
            var relato = new VendaxUsoRelato(VendaxResult.SCHEMA_VERSION, "tenant-live", "CS",
                    "conversation-live", "live-smoke-key", VendaxUsoRelato.Motivo.SEM_RESULT, callbackUsage);
            new VendaxResultSender("http://127.0.0.1:" + receiver.getAddress().getPort(), hmacSecret)
                    .relatarUso(relato);
        } finally {
            receiver.stop(0);
        }

        assertThat(path.get()).isEqualTo("/api/v1/public/archflow/uso");
        assertThat(signature.get()).isEqualTo(hmac(hmacSecret, timestamp.get(), body.get()));
        JsonNode sent = new ObjectMapper().readTree(body.get());
        JsonNode sentTurn = sent.path("uso").path("modelUsage").get(0);
        assertThat(sentTurn.path("requestedModel").asText()).isEqualTo("openrouter/google/gemini-2.5-flash-lite");
        assertThat(sentTurn.path("effectiveModel").asText()).isEqualTo(turn.effectiveModel());
        assertThat(sentTurn.path("effectiveProvider").asText()).isEqualTo(turn.effectiveProvider());
        assertThat(sentTurn.path("declaredCostUsd").asDouble()).isEqualTo(turn.declaredCostUsd());

        System.out.printf("LIVE_OPENROUTER_USAGE requestedModel=google/gemini-2.5-flash-lite effectiveModel=%s "
                        + "effectiveProvider=%s inputTokens=%s outputTokens=%s declaredCostUsd=%s signedCallback=true%n",
                turn.effectiveModel(), turn.effectiveProvider(), tokens == null ? null : tokens.inputTokenCount(),
                tokens == null ? null : tokens.outputTokenCount(), turn.declaredCostUsd());
    }

    private static String hmac(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
