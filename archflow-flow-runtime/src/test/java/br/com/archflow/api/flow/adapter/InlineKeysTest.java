package br.com.archflow.api.flow.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fora do servidor a chave vem só do ambiente: a escrita no nó sai da config do adapter (RCHFLW-37). */
@DisplayName("LangChainAdapterComponent.withoutInlineKeys")
class InlineKeysTest {

    @Test
    @DisplayName("tira o alias do designer, o nome do adapter e o nome próprio do embedding da OpenAI")
    void tiraAsChaves() {
        Map<String, Object> config = Map.of("apiKey", "a", "api.key", "b", "openai.api.key", "c",
                "model", "gpt", "base.url", "https://x");

        assertThat(LangChainAdapterComponent.withoutInlineKeys(config))
                .containsOnlyKeys("model", "base.url");
    }

    @Test
    @DisplayName("tira a chave do modelo reserva do OpenRouter, mas mantém o destino e o modelo dele")
    void tiraAChaveDoReserva() {
        Map<String, Object> config = Map.of("fallback.base.url", "http://host:11434/v1",
                "fallback.model.name", "llama3.1", "fallback.api.key", "chave-do-arquivo");

        assertThat(LangChainAdapterComponent.withoutInlineKeys(config))
                .containsOnlyKeys("fallback.base.url", "fallback.model.name");
    }

    @Test
    @DisplayName("sem chave no nó, devolve a mesma config")
    void semChave() {
        Map<String, Object> config = Map.of("model", "gpt");

        assertThat(LangChainAdapterComponent.withoutInlineKeys(config)).isSameAs(config);
        assertThat(LangChainAdapterComponent.withoutInlineKeys(null)).isEmpty();
    }
}
