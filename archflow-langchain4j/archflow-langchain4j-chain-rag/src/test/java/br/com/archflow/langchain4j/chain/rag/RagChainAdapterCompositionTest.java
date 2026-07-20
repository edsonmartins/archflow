package br.com.archflow.langchain4j.chain.rag;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.openai.OpenAiChatAdapter;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Testes de composição do {@link RagChainAdapter} com adapters de chat reais
 * (item 6.2 do plano de homologação).
 *
 * <p>Antes da correção, {@code configure()} exigia que o adapter de chat fosse
 * {@code instanceof ChatModel} — o que NENHUM chat adapter do archflow satisfaz
 * (eles encapsulam o modelo internamente). Com
 * {@link br.com.archflow.langchain4j.core.spi.ChatModelProvider}, o RAG obtém o
 * modelo subjacente do adapter. Nenhum teste faz chamada de rede: construir o
 * OpenAiChatModel com uma api key falsa não conecta a nada.
 */
@DisplayName("RagChainAdapter — composição com chat adapters (ChatModelProvider)")
class RagChainAdapterCompositionTest {

    private Map<String, Object> ragProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("embedding.provider", "fake-embedding");
        props.put("vectorstore.provider", "fake-vectorstore");
        props.put("languagemodel.provider", "openai");   // adapter REAL (OpenAiChatAdapter)
        props.put("api.key", "sk-test-fake-key");        // não faz rede na configuração
        return props;
    }

    @Test
    @DisplayName("configure() passa com OpenAiChatAdapter real via ChatModelProvider (antes falhava)")
    void configureSucceedsWithRealOpenAiChatAdapter() {
        RagChainAdapter adapter = new RagChainAdapter();

        // Antes: IllegalStateException "does not return a ChatModel" para 100% das combinações.
        assertThatCode(() -> adapter.configure(ragProperties()))
                .doesNotThrowAnyException();

        adapter.shutdown();
    }

    @Test
    @DisplayName("resolveChatModel extrai o modelo de um OpenAiChatAdapter configurado")
    void resolveChatModelFromConfiguredOpenAiAdapter() {
        OpenAiChatAdapter chatAdapter = new OpenAiChatAdapter();
        chatAdapter.configure(Map.of("api.key", "sk-test-fake-key"));

        ChatModel model = RagChainAdapter.resolveChatModel(chatAdapter, "openai");

        assertThat(model).isNotNull();
        assertThat(model).isSameAs(chatAdapter.getChatModel());
    }

    @Test
    @DisplayName("resolveChatModel aceita adapter que implementa ChatModel direto (retrocompat)")
    void resolveChatModelAcceptsDirectChatModel() {
        LangChainAdapter direct = mock(LangChainAdapter.class,
                withSettings().extraInterfaces(ChatModel.class));

        ChatModel model = RagChainAdapter.resolveChatModel(direct, "legacy");

        assertThat(model).isSameAs(direct);
    }

    @Test
    @DisplayName("resolveChatModel rejeita adapter que não expõe ChatModel")
    void resolveChatModelRejectsAdapterWithoutChatModel() {
        LangChainAdapter bare = mock(LangChainAdapter.class);

        assertThatThrownBy(() -> RagChainAdapter.resolveChatModel(bare, "no-model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-model")
                .hasMessageContaining("does not expose a ChatModel");
    }

    @Test
    @DisplayName("getChatModel de adapter não configurado lança IllegalStateException clara")
    void getChatModelOnUnconfiguredAdapterThrows() {
        OpenAiChatAdapter unconfigured = new OpenAiChatAdapter();

        assertThatThrownBy(unconfigured::getChatModel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
