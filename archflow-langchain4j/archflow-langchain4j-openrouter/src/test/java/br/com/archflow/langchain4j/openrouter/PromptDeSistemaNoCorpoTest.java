package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@code systemPrompt} do nó sai no corpo da requisição (RCHFLW-52), lido do socket de um servidor
 * local — afirmar sobre a config que o adapter recebeu não prova o que vai para o provedor.
 */
@DisplayName("OpenRouter: prompt de sistema do nó no corpo da requisição")
class PromptDeSistemaNoCorpoTest {

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"oi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    private HttpServer server;
    private final List<String> corpos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", troca -> {
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resposta = RESPOSTA.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    private OpenRouterChatAdapter adapter(Map<String, Object> extra) {
        Map<String, Object> props = new java.util.HashMap<>(Map.of(
                "api.key", "chave-de-teste",
                "model.name", "openai/gpt-6-luna",
                "base.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        props.putAll(extra);
        OpenRouterChatAdapter adapter = new OpenRouterChatAdapter();
        adapter.configure(props);
        return adapter;
    }

    private static ExecutionContext contexto() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());
    }

    @Test
    @DisplayName("chat: a primeira mensagem é a de sistema, com o texto do nó")
    void chatLevaOPrompt() throws Exception {
        adapter(Map.of("systemPrompt", "Responda apenas com SIM ou NAO."))
                .execute("chat", "o céu é azul?", contexto());

        assertThat(corpos).singleElement().satisfies(corpo -> {
            String semEspacos = corpo.replaceAll("\\s", "");
            assertThat(semEspacos).contains("{\"role\":\"system\",\"content\":\"RespondaapenascomSIMouNAO.\"}");
            assertThat(semEspacos.indexOf("\"role\":\"system\""))
                    .as("o prompt de sistema vem antes da mensagem do usuário")
                    .isLessThan(semEspacos.indexOf("\"role\":\"user\""));
        });
    }

    @Test
    @DisplayName("generate: o prompt também vai")
    void generateLevaOPrompt() throws Exception {
        adapter(Map.of("systemPrompt", "Seja breve.")).execute("generate", "oi", contexto());

        assertThat(corpos).singleElement()
                .satisfies(corpo -> assertThat(corpo.replaceAll("\\s", "")).contains("\"role\":\"system\""));
    }

    @Test
    @DisplayName("sem prompt no nó: nenhuma mensagem de sistema inventada")
    void semPromptNaoHaSistema() throws Exception {
        adapter(Map.of()).execute("chat", "oi", contexto());

        assertThat(corpos).singleElement()
                .satisfies(corpo -> assertThat(corpo.replaceAll("\\s", "")).doesNotContain("\"role\":\"system\""));
    }
}
