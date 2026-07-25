package br.com.archflow.api.flow;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Com a ponte de adapters, um nó de chat passa a acumular a conversa em
 * {@code context.getChatMemory()} — mas essa memória vive no heap. Ao retomar,
 * o resume construía uma memória vazia e a conversa sumia: um gate de aprovação
 * no meio de um diálogo devolvia o agente amnésico.
 */
@DisplayName("FlowStateChatMemory")
class FlowStateChatMemoryTest {

    private static ExecutionContext contextWithMemory() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());
    }

    @Test
    @DisplayName("captura e restaura a conversa preservando ordem e papéis")
    void roundTrip() {
        ExecutionContext original = contextWithMemory();
        original.getChatMemory().add(UserMessage.from("qual o status do pedido?"));
        original.getChatMemory().add(AiMessage.from("está a caminho"));

        FlowStateChatMemory.capture(original);

        // Simula a retomada: contexto novo, memória vazia, variáveis vindas do
        // estado durável.
        ExecutionContext resumed = contextWithMemory();
        resumed.set(FlowStateChatMemory.CHAT_MEMORY_KEY,
                original.get(FlowStateChatMemory.CHAT_MEMORY_KEY).orElseThrow());
        new FlowStateChatMemory().restore(resumed);

        List<ChatMessage> messages = resumed.getChatMemory().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(0)).singleText())
                .isEqualTo("qual o status do pedido?");
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(1)).text()).isEqualTo("está a caminho");
    }

    @Test
    @DisplayName("memória vazia não grava variável — a maioria dos fluxos não tem chat")
    void emptyMemoryWritesNothing() {
        ExecutionContext context = contextWithMemory();

        FlowStateChatMemory.capture(context);

        assertThat(context.get(FlowStateChatMemory.CHAT_MEMORY_KEY)).isEmpty();
    }

    @Test
    @DisplayName("contexto sem ChatMemory é ignorado sem erro")
    void contextWithoutMemoryIsIgnored() {
        ExecutionContext context = new DefaultExecutionContext("acme", "u", "run-1", null);

        FlowStateChatMemory.capture(context);
        new FlowStateChatMemory().restore(context);

        assertThat(context.get(FlowStateChatMemory.CHAT_MEMORY_KEY)).isEmpty();
    }

    @Test
    @DisplayName("mensagem ilegível é descartada sem custar as demais")
    void unreadableMessageDoesNotCostTheOthers() {
        ExecutionContext resumed = contextWithMemory();
        resumed.set(FlowStateChatMemory.CHAT_MEMORY_KEY, List.of(
                "{isto nao e json valido",
                dev.langchain4j.data.message.ChatMessageSerializer.messageToJson(
                        UserMessage.from("sobrevivi"))));

        new FlowStateChatMemory().restore(resumed);

        assertThat(resumed.getChatMemory().messages()).hasSize(1);
    }

    @Test
    @DisplayName("restaurar sem nada armazenado não mexe na memória")
    void nothingStoredMeansNoChange() {
        ExecutionContext context = contextWithMemory();
        context.getChatMemory().add(UserMessage.from("já estava aqui"));

        new FlowStateChatMemory().restore(context);

        assertThat(context.getChatMemory().messages()).hasSize(1);
    }
}
