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
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(uso).build();
        }

        dev.langchain4j.model.output.TokenUsage uso;
    }

    /** O ouvinte que o host põe no contexto; aqui, só anota. */
    private static br.com.archflow.model.engine.ExecutionContext contextoCom(List<String> relatos) {
        var contexto = new br.com.archflow.model.engine.DefaultExecutionContext("acme", "u", "exec-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());
        br.com.archflow.model.engine.ModelUsageListener ouvinte =
                (provedor, modelo, entrada, saida) ->
                        relatos.add(provedor + "|" + modelo + "|" + entrada + "|" + saida);
        contexto.set(br.com.archflow.model.engine.ModelUsageListener.CONTEXT_KEY, ouvinte);
        return contexto;
    }

    @Test
    @DisplayName("com ouvinte no contexto, generate e chat relatam provedor/modelo e tokens (RCHFLW-75)")
    void relataOUsoAoOuvinteDoContexto() {
        Gravador modelo = new Gravador();
        modelo.uso = new dev.langchain4j.model.output.TokenUsage(120, 30);
        List<String> relatos = new ArrayList<>();
        var contexto = contextoCom(relatos);

        String resposta = ChatTurn.generate(modelo, null, "oi", contexto, "openai", "gpt-4o-mini");
        ChatTurn.chat(modelo, "Seja breve.", contexto.getChatMemory(), "e agora?", contexto, "openai", "gpt-4o-mini");

        assertThat(resposta).isEqualTo("ok");
        assertThat(relatos).containsExactly("openai|openai/gpt-4o-mini|120|30", "openai|openai/gpt-4o-mini|120|30");
        // A volta sem prompt continua sendo só a mensagem do usuário.
        assertThat(modelo.chamadas.get(0)).containsExactly(UserMessage.from("oi"));
    }

    @Test
    @DisplayName("sem ouvinte no contexto, nada é relatado e a requisição é a mesma")
    void semOuvinteNadaMuda() {
        Gravador modelo = new Gravador();
        var contexto = new br.com.archflow.model.engine.DefaultExecutionContext("acme", "u", "exec-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());

        ChatTurn.generate(modelo, null, "oi", contexto, "openai", "gpt-4o-mini");

        assertThat(modelo.chamadas).singleElement()
                .satisfies(mensagens -> assertThat(mensagens).containsExactly(UserMessage.from("oi")));
    }

    @Test
    @DisplayName("resposta sem uso informado relata tokens nulos — não informado, nunca zero")
    void semUsoInformadoRelataNulo() {
        List<String> relatos = new ArrayList<>();

        ChatTurn.generate(new Gravador(), "Prompt", "oi", contextoCom(relatos), "anthropic", "claude");

        assertThat(relatos).containsExactly("anthropic|anthropic/claude|null|null");
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
