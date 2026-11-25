package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
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
 * Um nó {@code llm-chat} com {@code provider: openrouter} e <b>nada de chave no JSON</b>, executado
 * pelo adapter OpenRouter real contra um servidor local que fala o protocolo OpenAI.
 *
 * <p>O que se confere é o header {@code Authorization} lido do socket — a mesma regra do
 * {@code PreferenciaDeProvedorNoCorpoTest}: afirmar sobre a config resolvida não prova o que sai.
 * O servidor local é, conforme o caso, o "gateway do operador" (na lista) ou o "host do atacante"
 * (fora dela).
 */
@DisplayName("llm-chat: chave do tenant/plataforma no fio, e nunca para host fora da lista")
class ChaveDoNoLlmChatNoFioTest {

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"oi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    private HttpServer server;
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();

    private final PropertyTenantKeyResolver chaves = new PropertyTenantKeyResolver(
            Map.of("tenant_a", Map.of("openrouter", "chave-do-tenant-a")),
            Map.of(), "openrouter", "chave-da-plataforma");

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", troca -> {
            troca.getRequestBody().readAllBytes();
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
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

    private StepResult executar(BaseUrlPolicy politica, String tenant) {
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null, null, chaves);
        factory.setBaseUrlPolicy(politica);
        Map<String, Object> node = new HashMap<>();
        node.put("id", "s1");
        node.put("type", "llm-chat");
        node.put("componentId", "llm-chat");
        // Nenhuma chave aqui — só o destino, que é o que o RCHFLW-42 discute.
        node.put("config", Map.of("provider", "openrouter", "model", "openai/gpt-6-luna",
                "operation", "generate", "base.url", local()));
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(Map.of(
                "id", "wf", "metadata", Map.of("name", "chave"), "steps", List.of(node)));
        ExecutionContext ctx = new DefaultExecutionContext(tenant, "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", "diga oi");
        return flow.getSteps().get(0).execute(ctx).join();
    }

    @Test
    @DisplayName("host na lista: o tenant_a manda a chave dele, o SYSTEM manda a da plataforma")
    void chaveCertaParaCadaTenant() {
        BaseUrlPolicy comGateway = BaseUrlPolicy.of(List.of(local()));

        assertThat(executar(comGateway, "tenant_a").getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(executar(comGateway, "SYSTEM").getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(executar(comGateway, "tenant_sem_chave").getStatus()).isEqualTo(StepStatus.COMPLETED);

        assertThat(autorizacoes).containsExactly(
                "Bearer chave-do-tenant-a", "Bearer chave-da-plataforma", "Bearer chave-da-plataforma");
    }

    @Test
    @DisplayName("host fora da lista: o passo falha com [LLM-HOST] e nenhuma requisição chega")
    void hostForaDaListaNaoRecebeNada() {
        StepResult resultado = executar(BaseUrlPolicy.officialOnly(), "tenant_a");

        assertThat(resultado.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(String.valueOf(resultado.getOutput()) + resultado.getErrors())
                .contains("[LLM-HOST]").contains("127.0.0.1");
        assertThat(autorizacoes).as("a chave não pode ter saído").isEmpty();
    }
}
