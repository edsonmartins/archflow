package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Ponta a ponta, com o adapter OpenRouter real e um servidor local no lugar do provedor: o nó
 * {@code llm-chat} <b>como o designer grava</b> (sem provider, sem modelo, sem operação, com prompt de
 * sistema) executado com uma entrada JSON de variáveis. Confere-se o corpo lido do socket.
 *
 * <p>Junta RCHFLW-16 (herda o provider/modelo da plataforma), RCHFLW-51 (operação ausente e entrada
 * mapa) e RCHFLW-52 (o prompt de sistema chega ao modelo) — cada um sozinho já impedia o nó de
 * responder o que se configurou.
 */
@DisplayName("llm-chat do designer: o que sai no fio")
class LlmChatDoDesignerNoFioTest {

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"resposta"},"finish_reason":"stop"}],
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

    private String local() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Fábrica com archflow.llm.provider=openrouter, archflow.llm.model e base-url = o gateway local. */
    private DefaultFlowStepFactory fabrica() {
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null, null,
                new PropertyTenantKeyResolver(Map.of(), Map.of(), "openrouter", "chave-da-plataforma"));
        factory.setPlatformDefaultLLM(ResolvedLLMConfig.builder()
                .provider("openrouter").model("google/gemma-4-31b-it:free")
                .additionalConfig(Map.of("baseUrl", local())).build());
        factory.setBaseUrlPolicy(BaseUrlPolicy.of(List.of(local())));
        return factory;
    }

    /** Nó llm-chat como o designer grava: sem provider, modelo nem operação. */
    private static Map<String, Object> noDoDesigner(String id, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", "llm-chat");
        node.put("componentId", "llm-chat");
        node.put("config", config);
        return node;
    }

    @SafeVarargs
    private Flow fluxo(Map<String, Object>... nos) {
        return new DefaultWorkflowDeserializer(fabrica()).toFlow(Map.of(
                "id", "wf", "metadata", Map.of("name", "designer"), "steps", List.of(nos)));
    }

    private static ExecutionContext contexto() {
        // Como o servidor monta: toda execução tem a janela de 20 mensagens.
        return new DefaultExecutionContext("SYSTEM", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());
    }

    @Test
    @DisplayName("sem provider/operação, com prompt e entrada-mapa: modelo da plataforma, system e texto")
    void noDoDesignerResponde() {
        Flow flow = fluxo(noDoDesigner("s1", Map.of("systemPrompt", "Responda em uma frase.")));
        ExecutionContext ctx = contexto();
        // O que RunInputSeeding põe em "input" para a entrada {"message": "..."}.
        ctx.set("input", Map.of("message", "Qual a capital da Franca?"));

        StepResult result = flow.getSteps().get(0).execute(ctx).join();

        assertThat(result.getStatus()).as(String.valueOf(result.getOutput())).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).hasValue("resposta");
        assertThat(corpos).singleElement().satisfies(corpo -> {
            String c = corpo.replaceAll("\\s", "");
            assertThat(c).contains("\"model\":\"google/gemma-4-31b-it:free\"");
            assertThat(c).contains("{\"role\":\"system\",\"content\":\"Respondaemumafrase.\"}");
            assertThat(c).contains("{\"role\":\"user\",\"content\":\"QualacapitaldaFranca?\"}");
        });
    }

    @Test
    @DisplayName("dois nós de chat em sequência: o segundo não leva a conversa do primeiro")
    void segundoNoNaoHerdaAConversa() {
        Flow flow = fluxo(noDoDesigner("resumir", Map.of("systemPrompt", "Resuma.")),
                noDoDesigner("traduzir", Map.of("systemPrompt", "Traduza.")));
        ExecutionContext ctx = contexto();
        ctx.set("input", "um texto longo e sensível");

        flow.getSteps().get(0).execute(ctx).join();
        // O passo grava a saída ("resposta") em "input": é a entrada do seguinte.
        StepResult result = flow.getSteps().get(1).execute(ctx).join();

        assertThat(result.getStatus()).as(String.valueOf(result.getOutput())).isEqualTo(StepStatus.COMPLETED);
        assertThat(corpos).hasSize(2);
        String segundo = corpos.get(1).replaceAll("\\s", "");
        assertThat(segundo)
                .as("antes: [system B, user A, assistant A, user=resposta de A] — a conversa do outro nó, "
                        + "e a resposta dele duas vezes")
                .doesNotContain("\"role\":\"assistant\"")
                .doesNotContain("Resuma.")
                .doesNotContain("umtextolongoesensível");
        assertThat(ctx.getChatMemory().messages()).as("o nó do designer não escreve na memória da execução")
                .isEmpty();
    }

    /**
     * RCHFLW-101: executar com a entrada vazia deixava o {@code input} indefinido, e o nó falhava com o
     * texto técnico "Input must be a string for 'generate'". Agora o input é "" e é ele que o nó manda.
     */
    @Test
    @DisplayName("entrada vazia: o nó recebe \"\" (não um valor ausente) e responde")
    void entradaVazia() {
        Flow flow = fluxo(noDoDesigner("s1", Map.of("systemPrompt", "Conte uma piada curta.")));
        ExecutionContext ctx = contexto();
        RunInputSeeding.seedVariables(ctx, null);

        StepResult result = flow.getSteps().get(0).execute(ctx).join();

        assertThat(result.getStatus()).as(String.valueOf(result.getOutput())).isEqualTo(StepStatus.COMPLETED);
        assertThat(corpos).singleElement().satisfies(corpo -> assertThat(corpo.replaceAll("\\s", ""))
                .contains("{\"role\":\"user\",\"content\":\"\"}"));
    }

    @Test
    @DisplayName("llm.model na entrada da execução não troca o modelo do nó (nem sai com a chave governada)")
    void entradaNaoEscolheOModelo() {
        Flow flow = fluxo(noDoDesigner("s1", Map.of()));
        ExecutionContext ctx = contexto();
        // RunInputSeeding: {"llm.model": "...", "message": "oi"} vira variáveis da execução.
        ctx.set("llm.model", "algum/modelo-caro");
        ctx.set("input", "oi");

        StepResult result = flow.getSteps().get(0).execute(ctx).join();

        assertThat(result.getStatus()).as(String.valueOf(result.getOutput())).isEqualTo(StepStatus.COMPLETED);
        assertThat(corpos).singleElement().satisfies(corpo -> assertThat(corpo.replaceAll("\\s", ""))
                .contains("\"model\":\"google/gemma-4-31b-it:free\"")
                .doesNotContain("algum/modelo-caro"));
    }
}
