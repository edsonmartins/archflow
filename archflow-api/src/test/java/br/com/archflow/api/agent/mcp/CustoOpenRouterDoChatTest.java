package br.com.archflow.api.agent.mcp;

import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import br.com.archflow.api.agent.vendax.VendaxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("custo efetivo do chat OpenRouter")
class CustoOpenRouterDoChatTest {

    @Test
    @DisplayName("captura usage.cost da resposta HTTP original")
    void capturaCustoDeclarado() {
        assertThat(McpAgentRunner.custoOpenRouter("openrouter", response("""
                {"usage":{"cost":0.00425}}
                """))).isEqualTo(0.00425);
    }

    @Test
    @DisplayName("captura modelo servido e endpoint selecionado sem confundir com o pedido")
    void capturaRotaEfetiva() {
        var route = McpAgentRunner.usoOpenRouter("openrouter", response("""
                {"model":"google/gemini-2.5-flash-lite-2026-01-01","usage":{"cost":0.00425},
                 "openrouter_metadata":{"endpoints":{"available":[
                   {"provider":"Google AI Studio","selected":true}]}}}
                """));
        assertThat(route.model()).isEqualTo("google/gemini-2.5-flash-lite-2026-01-01");
        assertThat(route.provider()).isEqualTo("Google AI Studio");
        assertThat(route.costUsd()).isEqualTo(0.00425);
    }

    @Test
    @DisplayName("captura usage.cost do evento final de uma resposta SSE")
    void capturaCustoDaRespostaSse() {
        var metadata = OpenAiChatResponseMetadata.builder()
                .rawServerSentEvents(List.of(
                        new ServerSentEvent("message", "{\"model\":\"served/model\",\"openrouter_metadata\":{"
                                + "\"endpoints\":{\"available\":[{\"provider\":\"Provider SSE\",\"selected\":true}]}}}"),
                        new ServerSentEvent("message", "{\"usage\":{\"cost\":0.006}}")))
                .build();
        var response = ChatResponse.builder().aiMessage(AiMessage.from("resposta"))
                .metadata(metadata).build();

        assertThat(McpAgentRunner.custoOpenRouter("openrouter", response)).isEqualTo(0.006);
        assertThat(McpAgentRunner.usoOpenRouter("openrouter", response).model()).isEqualTo("served/model");
        assertThat(McpAgentRunner.usoOpenRouter("openrouter", response).provider()).isEqualTo("Provider SSE");
    }

    @Test
    @DisplayName("leva o custo por turno até o resumo do callback")
    void propagaCustoAteCallback() {
        ContadorDeUso counter = new ContadorDeUso();
        var options = new McpAgentRunner.Options(null, null, null, 1, null, null,
                null, null, Set.of(), counter);

        McpAgentRunner.somarUso(options, "openrouter", "openrouter/model-x",
                response("{\"model\":\"actual/model-v2\",\"usage\":{\"cost\":0.00425},"
                        + "\"openrouter_metadata\":{\"endpoints\":{\"available\":["
                        + "{\"provider\":\"Provider X\",\"selected\":true}]}}}"));

        var summary = counter.resumo().orElseThrow();
        assertThat(summary.custoDeclaradoUsd()).isEqualTo(0.00425);
        assertThat(VendaxResult.Uso.de(summary, null).declaredCostUsd()).isEqualTo(0.00425);
        var turn = VendaxResult.Uso.de(summary, null).modelUsage().getFirst();
        assertThat(turn.requestedModel()).isEqualTo("openrouter/model-x");
        assertThat(turn.effectiveModel()).isEqualTo("actual/model-v2");
        assertThat(turn.effectiveProvider()).isEqualTo("Provider X");
    }

    @Test
    @DisplayName("omite o custo total se um turno não trouxer usage.cost")
    void custoParcialNaoViraTotal() {
        ContadorDeUso counter = new ContadorDeUso();
        var options = new McpAgentRunner.Options(null, null, null, 1, null, null,
                null, null, Set.of(), counter);

        McpAgentRunner.somarUso(options, "openrouter", "openrouter/model-x",
                response("{\"usage\":{\"cost\":0.00425}}"));
        McpAgentRunner.somarUso(options, "openrouter", "openrouter/model-x", response("{}"));

        assertThat(counter.resumo().orElseThrow().custoDeclaradoUsd()).isNull();
    }

    @Test
    @DisplayName("custo ausente, negativo, não numérico ou outro provider permanece desconhecido")
    void custoInvalidoOuAusentePermaneceDesconhecido() {
        assertThat(McpAgentRunner.custoOpenRouter("openrouter", response("{}"))).isNull();
        assertThat(McpAgentRunner.custoOpenRouter("openrouter",
                response("{\"usage\":{\"cost\":-0.1}}"))).isNull();
        assertThat(McpAgentRunner.custoOpenRouter("openrouter",
                response("{\"usage\":{\"cost\":\"0.1\"}}"))).isNull();
        assertThat(McpAgentRunner.custoOpenRouter("openai", response(
                "{\"usage\":{\"cost\":0.1}}"))).isNull();
    }

    private static ChatResponse response(String body) {
        var raw = SuccessfulHttpResponse.builder().statusCode(200).body(body).build();
        return ChatResponse.builder().aiMessage(AiMessage.from("resposta")).metadata(OpenAiChatResponseMetadata.builder()
                .rawHttpResponse(raw).build()).build();
    }
}
