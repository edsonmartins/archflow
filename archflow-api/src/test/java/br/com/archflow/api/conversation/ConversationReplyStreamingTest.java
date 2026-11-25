package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.service.DefaultConversationService;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-68: a resposta da conversa chega em partes. Antes era o {@code ChatModel.chat} bloqueante —
 * no stream só passavam {@code chat/start}, um {@code chat/message} com o texto inteiro e
 * {@code chat/end}, e a bolha ficava vazia durante toda a geração.
 */
@DisplayName("Resposta da conversa em streaming (RCHFLW-68)")
class ConversationReplyStreamingTest {

    /** Registry que anota cada evento publicado, em ordem. */
    private static final class RegistryQueAnota extends EventStreamRegistry {
        final List<ArchflowEvent> eventos = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int broadcast(String channel, ArchflowEvent event) {
            eventos.add(event);
            return super.broadcast(channel, event);
        }
    }

    /** Modelo em streaming que entrega o roteiro dado ao handler, na thread de quem chamou. */
    private static StreamingChatModel modelo(Consumer<StreamingChatResponseHandler> roteiro) {
        return new StreamingChatModel() {
            @Override
            public void chat(String userMessage, StreamingChatResponseHandler handler) {
                roteiro.accept(handler);
            }
        };
    }

    private RegistryQueAnota registry;
    private ConversationService conversas;
    private LLMConfigResolver resolver;
    private ConversationReplyService service;

    @BeforeEach
    void monta() {
        registry = new RegistryQueAnota();
        conversas = new DefaultConversationService(
                new ConversationManager(Duration.ofMinutes(30), new InMemorySuspendedConversationStore()));
        conversas.addMessage(ConversationMessage.user("conv-1", "Explique o archflow"));
        resolver = mock(LLMConfigResolver.class);
        ResolvedLLMConfig plataforma = new ResolvedLLMConfig("openrouter", "m", 0.0, 256, 30, Map.of());
        when(resolver.resolve(any())).thenReturn(plataforma);
        service = new ConversationReplyService(conversas, registry, resolver, plataforma);
    }

    @AfterEach
    void desmonta() {
        registry.shutdown();
    }

    private List<String> tipos() {
        return registry.eventos.stream()
                .filter(e -> e.getDomain() == ArchflowDomain.CHAT)
                .map(e -> e.getType().getValue())
                .toList();
    }

    @Test
    @DisplayName("cada parcial vira um chat/delta enquanto chega; depois chat/message com o texto e chat/end")
    void deltasDepoisMensagemEFim() {
        when(resolver.resolveStreamingModel(any())).thenReturn(modelo(handler -> {
            handler.onPartialResponse("O arch");
            handler.onPartialResponse("flow é ");
            handler.onPartialResponse("um framework.");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("O archflow é um framework.")).build());
        }));

        service.reply("conv-1", "tenant-a");

        assertThat(tipos()).containsExactly("start", "delta", "delta", "delta", "message", "end");
        assertThat(registry.eventos).filteredOn(e -> e.getType() == ArchflowEventType.DELTA)
                .extracting(e -> e.getData().get("content"))
                .containsExactly("O arch", "flow é ", "um framework.");
        ArchflowEvent mensagem = registry.eventos.stream()
                .filter(e -> e.getType() == ArchflowEventType.MESSAGE).findFirst().orElseThrow();
        assertThat(mensagem.getData()).containsEntry("content", "O archflow é um framework.")
                .containsEntry("role", "assistant");
        assertThat(conversas.getMessages("conv-1")).last()
                .extracting(ConversationMessage::content).isEqualTo("O archflow é um framework.");
        assertThat(mensagem.getData()).containsEntry("messageId",
                conversas.getMessages("conv-1").get(1).id());
        verify(resolver, never()).resolveModel(any());
    }

    @Test
    @DisplayName("erro no meio do streaming vira chat/error, sem mensagem gravada nem segunda chamada ao modelo")
    void erroNoMeioViraError() {
        when(resolver.resolveStreamingModel(any())).thenReturn(modelo(handler -> {
            handler.onPartialResponse("Começo");
            handler.onError(new RuntimeException("conexão caiu"));
        }));

        service.reply("conv-1", "tenant-a");

        assertThat(tipos()).containsExactly("start", "delta", "error");
        assertThat(registry.eventos.get(registry.eventos.size() - 1).getData().get("message"))
                .asString().contains("conexão caiu");
        assertThat(conversas.getMessages("conv-1")).hasSize(1);
        verify(resolver, never()).resolveModel(any());
    }

    @Test
    @DisplayName("provider que declara não ter streaming segue o caminho bloqueante")
    void providerSemStreamingBloqueante() {
        ResolvedLLMConfig hf = new ResolvedLLMConfig("huggingface", "m", 0.0, 256, 30, Map.of());
        when(resolver.resolve(any())).thenReturn(hf);
        dev.langchain4j.model.chat.ChatModel bloqueante = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(bloqueante.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("inteira");
        when(resolver.resolveModel(any())).thenReturn(bloqueante);

        service.reply("conv-1", "tenant-a");

        assertThat(tipos()).containsExactly("start", "message", "end");
        verify(resolver, never()).resolveStreamingModel(any());
    }
}
