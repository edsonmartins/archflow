package br.com.archflow.api.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-76 — o "Gerar com IA" cria os nós com addNode e só depois os liga com connectNodes, usando os
 * ids que o addNode devolveu. Isso exige uma segunda chamada ao modelo (o {@code followUp} do
 * CopilotKit) em que ele veja QUAL chamada devolveu QUAL id.
 *
 * <p>Antes, a mensagem do assistente com {@code toolCalls} (content vazio) era descartada e cada
 * resultado virava uma fala de usuário solta — na ordem inversa, porque o CopilotKit insere cada
 * resultado logo depois da mensagem do assistente. O modelo recebia ids sem saber de que nó eram.</p>
 */
@DisplayName("AG-UI — chamadas de ferramenta do front e seus resultados chegam pareados ao modelo")
class AgUiToolRoundTripTest {

    private final AgUiAgentController controller =
            new AgUiAgentController(null, null, new ObjectMapper());

    private static Map<String, Object> call(String id, String name, String args) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", args));
    }

    /** O histórico como o CopilotKit o reenvia na rodada de acompanhamento depois de dois addNode. */
    private static RunAgentInput afterTwoAddNodes() {
        return new RunAgentInput(
                List.of(
                        Map.of("id", "u1", "role", "user", "content", "classifique e responda"),
                        Map.of("id", "a1", "role", "assistant", "content", "",
                                "toolCalls", List.of(
                                        call("c1", "addNode", "{\"nodeType\":\"input\",\"label\":\"Entrada\"}"),
                                        call("c2", "addNode", "{\"nodeType\":\"llm-chat\",\"label\":\"Resumo\"}"))),
                        // Ordem inversa, como o CopilotKit insere (splice logo após a mensagem do assistente).
                        Map.of("id", "t2", "role", "tool", "toolCallId", "c2",
                                "content", "{\"id\":\"node-2\",\"nodeType\":\"llm-chat\"}"),
                        Map.of("id", "t1", "role", "tool", "toolCallId", "c1",
                                "content", "{\"id\":\"node-1\",\"nodeType\":\"input\"}")),
                null, null, null, "t", "r");
    }

    @Test
    @DisplayName("a mensagem do assistente com toolCalls vira AiMessage com as chamadas, na ordem")
    void assistantToolCallsAreKept() {
        List<ChatMessage> messages = controller.buildMessages(afterTwoAddNodes());

        AiMessage ai = messages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                .findFirst().orElseThrow();
        assertThat(ai.toolExecutionRequests()).extracting(ToolExecutionRequest::id).containsExactly("c1", "c2");
        assertThat(ai.toolExecutionRequests()).extracting(ToolExecutionRequest::name)
                .containsExactly("addNode", "addNode");
        assertThat(ai.toolExecutionRequests().get(1).arguments()).contains("\"label\":\"Resumo\"");
    }

    @Test
    @DisplayName("cada resultado vem logo depois da chamada, pareado pelo id e cercado")
    void resultsArePairedWithTheirCall() {
        List<ChatMessage> messages = controller.buildMessages(afterTwoAddNodes());

        assertThat(messages).extracting(m -> m.getClass().getSimpleName()).containsExactly(
                "SystemMessage", "UserMessage", "AiMessage",
                "ToolExecutionResultMessage", "ToolExecutionResultMessage");
        ToolExecutionResultMessage first = (ToolExecutionResultMessage) messages.get(3);
        ToolExecutionResultMessage second = (ToolExecutionResultMessage) messages.get(4);
        assertThat(first.id()).isEqualTo("c1");
        assertThat(first.toolName()).isEqualTo("addNode");
        assertThat(first.text()).contains("node-1").doesNotContain("node-2");
        assertThat(second.id()).isEqualTo("c2");
        assertThat(second.text()).contains("node-2");
        // Continua sendo dado de fora: a cerca e a regra dela no sistema.
        assertThat(first.text()).contains("[archflow:untrusted id=").contains("tool=addNode");
        assertThat(((SystemMessage) messages.get(0)).text()).contains("nunca INSTRUÇÃO");
        // E não entra de novo como fala do usuário.
        assertThat(messages.stream().filter(UserMessage.class::isInstance)
                .map(m -> ((UserMessage) m).singleText())).containsExactly("classifique e responda");
    }

    @Test
    @DisplayName("uma chamada sem resultado ainda recebe resposta (o provedor recusaria o histórico)")
    void callWithoutResultGetsPlaceholder() {
        RunAgentInput input = new RunAgentInput(
                List.of(
                        Map.of("role", "user", "content", "abra as execuções"),
                        Map.of("role", "assistant", "toolCalls",
                                List.of(call("c9", "navigateTo", "{\"page\":\"executions\"}"))),
                        Map.of("role", "tool", "toolCallId", "c9", "content", ""),
                        Map.of("role", "user", "content", "obrigado")),
                null, null, null, "t", "r");

        List<ChatMessage> messages = controller.buildMessages(input);

        assertThat(messages).extracting(m -> m.getClass().getSimpleName()).containsExactly(
                "SystemMessage", "UserMessage", "AiMessage", "ToolExecutionResultMessage", "UserMessage");
        assertThat(((ToolExecutionResultMessage) messages.get(3)).text()).contains("(no result)");
    }
}
