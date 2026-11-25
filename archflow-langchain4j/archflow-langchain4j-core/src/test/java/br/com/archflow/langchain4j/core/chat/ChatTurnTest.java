package br.com.archflow.langchain4j.core.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O prompt de sistema do nó chega ao modelo (RCHFLW-52) — e não entra na memória da execução.
 */
@DisplayName("ChatTurn: prompt de sistema do nó")
class ChatTurnTest {

    /** Modelo que grava as mensagens de cada chamada e responde "ok". */
    private static final class Gravador implements ChatModel {
        final List<List<ChatMessage>> chamadas = new ArrayList<>();
        boolean falhar;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            chamadas.add(List.copyOf(request.messages()));
            if (falhar) {
                throw new IllegalStateException("provedor fora");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }
    }

    @Test
    @DisplayName("generate com prompt: SystemMessage na frente da mensagem do usuário")
    void generateLevaOPrompt() {
        Gravador modelo = new Gravador();

        String resposta = ChatTurn.generate(modelo, "Responda em francês.", "oi");

        assertThat(resposta).isEqualTo("ok");
        assertThat(modelo.chamadas).singleElement().satisfies(mensagens -> {
            assertThat(mensagens).hasSize(2);
            assertThat(mensagens.get(0)).isEqualTo(SystemMessage.from("Responda em francês."));
            assertThat(mensagens.get(1)).isEqualTo(UserMessage.from("oi"));
        });
    }

    @Test
    @DisplayName("generate sem prompt: só a mensagem do usuário, como antes")
    void generateSemPrompt() {
        Gravador modelo = new Gravador();

        ChatTurn.generate(modelo, null, "oi");

        assertThat(modelo.chamadas).singleElement()
                .satisfies(mensagens -> assertThat(mensagens).containsExactly(UserMessage.from("oi")));
    }

    @Test
    @DisplayName("chat: prompt na frente, histórico no meio, e o prompt não vai para a memória")
    void chatComMemoria() {
        Gravador modelo = new Gravador();
        ChatMemory memoria = MessageWindowChatMemory.withMaxMessages(20);
        memoria.add(UserMessage.from("antes"));
        memoria.add(AiMessage.from("resposta antes"));

        ChatTurn.chat(modelo, "Seja breve.", memoria, "agora");

        assertThat(modelo.chamadas).singleElement().satisfies(mensagens -> assertThat(mensagens).containsExactly(
                SystemMessage.from("Seja breve."), UserMessage.from("antes"), AiMessage.from("resposta antes"),
                UserMessage.from("agora")));
        assertThat(memoria.messages())
                .as("a janela de 20 mensagens não pode expulsar as instruções nem passá-las ao próximo nó")
                .noneMatch(m -> m instanceof SystemMessage)
                .containsExactly(UserMessage.from("antes"), AiMessage.from("resposta antes"),
                        UserMessage.from("agora"), AiMessage.from("ok"));
    }

    @Test
    @DisplayName("chat que falha não deixa a pergunta órfã na memória")
    void falhaNaoSujaAMemoria() {
        Gravador modelo = new Gravador();
        modelo.falhar = true;
        ChatMemory memoria = MessageWindowChatMemory.withMaxMessages(20);

        assertThatThrownBy(() -> ChatTurn.chat(modelo, null, memoria, "oi"))
                .hasMessageContaining("provedor fora");
        assertThat(memoria.messages()).as("o fallback gravaria a mesma pergunta de novo").isEmpty();
    }

    @Test
    @DisplayName("systemPromptOf: em branco é ausente")
    void promptEmBrancoEAusente() {
        assertThat(ChatTurn.systemPromptOf(Map.of("systemPrompt", "  "))).isNull();
        assertThat(ChatTurn.systemPromptOf(Map.of())).isNull();
        assertThat(ChatTurn.systemPromptOf(null)).isNull();
        assertThat(ChatTurn.systemPromptOf(Map.of("systemPrompt", "x"))).isEqualTo("x");
    }
}
