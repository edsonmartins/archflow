package br.com.archflow.api.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;

class LangChainKnowledgeEmbeddingModelTest {

    @Test
    void adaptsProviderAndDefensivelyCopiesVector() {
        float[] providerVector = {1, 2, 3};
        EmbeddingModel provider = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment text) {
                assertThat(text.text()).isEqualTo("semantic text");
                return Response.from(Embedding.from(providerVector));
            }
        };
        var model = new LangChainKnowledgeEmbeddingModel(provider, 3);

        float[] result = model.embed("semantic text");

        assertThat(result).containsExactly(1, 2, 3);
        result[0] = 99;
        assertThat(providerVector[0]).isEqualTo(1);
        assertThat(model.dimension()).isEqualTo(3);
    }

    @Test
    void rejectsProviderDimensionMismatch() {
        EmbeddingModel provider = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment text) {
                return Response.from(Embedding.from(new float[]{1, 2}));
            }
        };

        assertThatThrownBy(() ->
                new LangChainKnowledgeEmbeddingModel(provider, 3).embed("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension 2")
                .hasMessageContaining("expected 3");
    }

    @Test
    void batchesTextsThroughProvider() {
        EmbeddingModel provider = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> texts) {
                assertThat(texts).extracting(TextSegment::text)
                        .containsExactly("first", "second");
                return Response.from(List.of(
                        Embedding.from(new float[]{1, 0}),
                        Embedding.from(new float[]{0, 1})));
            }
        };

        var result = new LangChainKnowledgeEmbeddingModel(provider, 2)
                .embedAll(List.of("first", "second"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(1, 0);
        assertThat(result.get(1)).containsExactly(0, 1);
    }

    @Test
    void wrapsProviderFailureWithoutLeakingConfiguration() {
        EmbeddingModel provider = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment text) {
                throw new RuntimeException("upstream unavailable");
            }
        };

        assertThatThrownBy(() ->
                new LangChainKnowledgeEmbeddingModel(provider, 3).embed("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Embedding provider request failed")
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
