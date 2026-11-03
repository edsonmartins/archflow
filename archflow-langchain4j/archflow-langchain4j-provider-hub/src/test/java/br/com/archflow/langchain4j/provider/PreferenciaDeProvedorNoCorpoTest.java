package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Preferência de provedor (ZDR) no corpo cru que chega ao servidor.
 *
 * <h2>Por que o corpo cru</h2>
 *
 * <p>O {@link ReasoningNoCorpoTest} confere a config que chega ao hub — não prova que o campo sai na
 * requisição. Aqui o servidor é local e fala o protocolo OpenAI; o que se confere é o que ele leu do
 * socket. É a única forma de pegar o defeito que motivou isto: o caminho em fluxo não levava nem o
 * {@code reasoning}, e a config resolvida estava certa o tempo todo.
 *
 * <h2>Por que importa</h2>
 *
 * <p>Sem o campo, ZDR vale só pela caixa marcada na conta do OpenRouter. Trocar a chave ou desmarcar a
 * opção manda a conversa do cliente a um provedor que retém — sem erro, sem log, sem número errado.
 */
@DisplayName("preferência de provedor (ZDR) no corpo da requisição")
class PreferenciaDeProvedorNoCorpoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProviderPreferences ZDR = new ProviderPreferences(true, "deny");

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"oi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    private static final String RESPOSTA_EM_FLUXO = """
            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":"oi"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
            "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private HttpServer server;
    private final List<String> corpos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        LLMProviderHub.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", troca -> {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            corpos.add(corpo);
            boolean fluxo = JSON.readTree(corpo).path("stream").asBoolean(false);
            byte[] resposta = (fluxo ? RESPOSTA_EM_FLUXO : RESPOSTA).getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", fluxo ? "text/event-stream" : "application/json");
            troca.sendResponseHeaders(200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
        LLMProviderHub.reset();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private ResolvedLLMConfig plataforma(String provider) {
        return ResolvedLLMConfig.builder()
                .provider(provider).model("google/gemini-2.5-flash-lite").maxTokens(256)
                .temperature(0.2)
                .additionalConfig(Map.of("apiKey", "k", "baseUrl", baseUrl()))
                .build();
    }

    private static DefaultLLMConfigResolver resolvedor(ProviderPreferences prefs, boolean required) {
        return new DefaultLLMConfigResolver(LLMProviderHub.getInstance(), TenantKeyResolver.NOOP,
                TierModelResolver.NOOP, prefs, required);
    }

    private static LLMResolutionRequest pedido(ResolvedLLMConfig plataforma, LLMConfigPatch passo) {
        return LLMResolutionRequest.builder(plataforma).stepPatch(passo).build();
    }

    private static void emFluxo(StreamingChatModel modelo) throws Exception {
        CompletableFuture<ChatResponse> fim = new CompletableFuture<>();
        modelo.chat("oi", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String parcial) {
            }

            @Override
            public void onCompleteResponse(ChatResponse resposta) {
                fim.complete(resposta);
            }

            @Override
            public void onError(Throwable erro) {
                fim.completeExceptionally(erro);
            }
        });
        fim.get(10, TimeUnit.SECONDS);
    }

    private JsonNode unicoCorpo() throws IOException {
        assertThat(corpos).hasSize(1);
        return JSON.readTree(corpos.get(0));
    }

    @Nested
    @DisplayName("OpenRouter com a preferência ligada")
    class Ligada {

        @Test
        @DisplayName("caminho 1 (bloqueante): o corpo traz provider {zdr, data_collection}")
        void bloqueante() throws Exception {
            ChatModel modelo = resolvedor(ZDR, false)
                    .resolveModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty()));
            modelo.chat("oi");

            JsonNode provider = unicoCorpo().get("provider");
            assertThat(provider).isNotNull();
            assertThat(JSON.convertValue(provider, Map.class))
                    .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
        }

        @Test
        @DisplayName("caminho 2 (em fluxo): o corpo traz provider {zdr, data_collection}")
        void emFluxoLeva() throws Exception {
            StreamingChatModel modelo = resolvedor(ZDR, false)
                    .resolveStreamingModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty()));
            emFluxo(modelo);

            JsonNode corpo = unicoCorpo();
            assertThat(corpo.path("stream").asBoolean()).as("é mesmo a requisição em fluxo").isTrue();
            assertThat(JSON.convertValue(corpo.get("provider"), Map.class))
                    .as("o buraco maior: o streaming não levava campo de topo nenhum")
                    .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
        }

        @Test
        @DisplayName("caminho 1: com reasoning declarado, os dois campos convivem")
        void bloqueanteComReasoning() throws Exception {
            LLMConfigPatch passo = LLMConfigPatch.fromMap(Map.of("reasoning", Map.of("effort", "none")));
            resolvedor(ZDR, false).resolveModel(pedido(plataforma("openrouter"), passo)).chat("oi");

            JsonNode corpo = unicoCorpo();
            assertThat(corpo.path("reasoning").path("effort").asText())
                    .as("customParameters substitui o mapa: dois chamados separados apagariam um")
                    .isEqualTo("none");
            assertThat(corpo.path("provider").path("zdr").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("caminho 2: com reasoning declarado, os dois campos convivem")
        void emFluxoComReasoning() throws Exception {
            LLMConfigPatch passo = LLMConfigPatch.fromMap(Map.of("reasoning", Map.of("effort", "none")));
            emFluxo(resolvedor(ZDR, false)
                    .resolveStreamingModel(pedido(plataforma("openrouter"), passo)));

            JsonNode corpo = unicoCorpo();
            assertThat(corpo.path("reasoning").path("effort").asText()).isEqualTo("none");
            assertThat(corpo.path("provider").path("data_collection").asText()).isEqualTo("deny");
        }

        @Test
        @DisplayName("só zdr declarado: só zdr vai (additionalProperties: false)")
        void soOQueFoiDeclarado() throws Exception {
            resolvedor(new ProviderPreferences(true, null), false)
                    .resolveModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty())).chat("oi");

            assertThat(JSON.convertValue(unicoCorpo().get("provider"), Map.class))
                    .isEqualTo(Map.of("zdr", true));
        }
    }

    @Nested
    @DisplayName("required")
    class Obrigatoria {

        @Test
        @DisplayName("bloqueante: sem preferência, a criação falha e nenhuma requisição sai")
        void bloqueanteFalha() {
            assertThatThrownBy(() -> resolvedor(ProviderPreferences.NONE, true)
                    .resolveModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("provider-preferences.required");
            assertThat(corpos).isEmpty();
        }

        @Test
        @DisplayName("em fluxo: sem preferência, a criação falha e nenhuma requisição sai")
        void emFluxoFalha() {
            assertThatThrownBy(() -> resolvedor(ProviderPreferences.NONE, true)
                    .resolveStreamingModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(corpos).isEmpty();
        }

        @Test
        @DisplayName("com a preferência configurada, required não atrapalha")
        void comPreferenciaPassa() throws Exception {
            resolvedor(ZDR, true)
                    .resolveModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty())).chat("oi");

            assertThat(unicoCorpo().path("provider").path("zdr").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("provedor que não é OpenRouter não é afetado pelo required")
        void outroProvedorNaoExige() throws Exception {
            resolvedor(ProviderPreferences.NONE, true)
                    .resolveModel(pedido(plataforma("openai"), LLMConfigPatch.empty())).chat("oi");

            assertThat(corpos).hasSize(1);
        }
    }

    @Nested
    @DisplayName("onde o campo não pode ir")
    class NaoVai {

        @Test
        @DisplayName("OpenAI direto com a preferência ligada: o corpo não traz provider (bloqueante)")
        void openAiBloqueante() throws Exception {
            resolvedor(ZDR, false)
                    .resolveModel(pedido(plataforma("openai"), LLMConfigPatch.empty())).chat("oi");

            assertThat(unicoCorpo().has("provider"))
                    .as("um campo desconhecido pode virar 400 fora do OpenRouter").isFalse();
        }

        @Test
        @DisplayName("OpenAI direto com a preferência ligada: o corpo não traz provider (em fluxo)")
        void openAiEmFluxo() throws Exception {
            emFluxo(resolvedor(ZDR, false)
                    .resolveStreamingModel(pedido(plataforma("openai"), LLMConfigPatch.empty())));

            assertThat(unicoCorpo().has("provider")).isFalse();
        }

        @Test
        @DisplayName("outro OpenAI-compatível (DeepSeek) também não recebe")
        void outroCompativel() throws Exception {
            resolvedor(ZDR, false)
                    .resolveModel(pedido(plataforma("deepseek"), LLMConfigPatch.empty())).chat("oi");

            assertThat(unicoCorpo().has("provider")).isFalse();
        }
    }

    /**
     * Controle. "Byte a byte o de hoje" é literal: o corpo enviado com a preferência desligada é
     * comparado ao de um modelo construído como o hub construía antes desta mudança — o builder do
     * langchain4j com os mesmos parâmetros e nada mais.
     */
    @Nested
    @DisplayName("preferência desligada e required=false: corpo inalterado")
    class Controle {

        @Test
        @DisplayName("bloqueante: byte a byte o mesmo de um OpenAiChatModel sem customParameters")
        void bloqueante() throws Exception {
            resolvedor(ProviderPreferences.NONE, false)
                    .resolveModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty())).chat("oi");
            OpenAiChatModel.builder().baseUrl(baseUrl()).apiKey("k")
                    .modelName("google/gemini-2.5-flash-lite").temperature(0.2)
                    .timeout(Duration.ofSeconds(30)).maxTokens(256).build()
                    .chat("oi");

            assertThat(corpos).hasSize(2);
            assertThat(corpos.get(0)).isEqualTo(corpos.get(1));
            assertThat(corpos.get(0)).doesNotContain("\"provider\"");
        }

        @Test
        @DisplayName("em fluxo: byte a byte o mesmo de um OpenAiStreamingChatModel sem customParameters")
        void emFluxoControle() throws Exception {
            emFluxo(resolvedor(ProviderPreferences.NONE, false)
                    .resolveStreamingModel(pedido(plataforma("openrouter"), LLMConfigPatch.empty())));
            emFluxo(OpenAiStreamingChatModel.builder().baseUrl(baseUrl()).apiKey("k")
                    .modelName("google/gemini-2.5-flash-lite").temperature(0.2)
                    .timeout(Duration.ofSeconds(30)).maxTokens(256).build());

            assertThat(corpos).hasSize(2);
            assertThat(corpos.get(0)).isEqualTo(corpos.get(1));
        }
    }
}
