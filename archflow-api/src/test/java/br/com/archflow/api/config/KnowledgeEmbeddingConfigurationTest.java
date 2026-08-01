package br.com.archflow.api.config;

import br.com.archflow.api.knowledge.HashEmbeddingModel;
import br.com.archflow.api.knowledge.LangChainKnowledgeEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeEmbeddingConfigurationTest {
    private final ArchflowBeanConfiguration configuration =
            new ArchflowBeanConfiguration();

    @Test
    void hashDefaultIsExplicitlyDevelopmentOnly() {
        assertThat(configuration.knowledgeEmbeddingModel())
                .isInstanceOf(HashEmbeddingModel.class)
                .extracting(model -> ((HashEmbeddingModel) model).dimension())
                .isEqualTo(128);
    }

    @Test
    void createsOpenAiProviderWithoutCallingNetwork() {
        var model = configuration.openAiKnowledgeEmbeddingModel(
                "test-key", "text-embedding-3-small", "",
                Duration.ofSeconds(20), 2);

        assertThat(model).isInstanceOf(LangChainKnowledgeEmbeddingModel.class);
        assertThat(model.dimension()).isEqualTo(128);
    }

    @Test
    void rejectsMissingKeyAndInvalidRetryPolicy() {
        assertThatThrownBy(() -> configuration.openAiKnowledgeEmbeddingModel(
                "", "text-embedding-3-small", "", Duration.ofSeconds(20), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");

        assertThatThrownBy(() -> configuration.openAiKnowledgeEmbeddingModel(
                "test-key", "text-embedding-3-small", "", Duration.ofSeconds(20), 11))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 0 and 10");
    }
}
