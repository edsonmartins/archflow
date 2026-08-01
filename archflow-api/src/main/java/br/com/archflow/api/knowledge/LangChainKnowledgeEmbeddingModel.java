package br.com.archflow.api.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.Objects;
import java.util.List;

/** Adapts a production LangChain4j embedding provider to the knowledge pipeline. */
public class LangChainKnowledgeEmbeddingModel implements KnowledgeEmbeddingModel {
    private final EmbeddingModel delegate;
    private final int dimension;

    public LangChainKnowledgeEmbeddingModel(EmbeddingModel delegate, int dimension) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be positive");
        this.dimension = dimension;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("texts cannot contain blank values");
        }
        try {
            var segments = texts.stream().map(TextSegment::from).toList();
            var embeddings = delegate.embedAll(segments).content();
            if (embeddings.size() != texts.size()) {
                throw new IllegalStateException(
                        "Embedding provider returned " + embeddings.size()
                                + " vectors; expected " + texts.size());
            }
            return embeddings.stream().map(embedding -> {
                float[] vector = embedding.vector();
                requireDimension(vector);
                return vector.clone();
            }).toList();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Embedding provider request failed", failure);
        }
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text cannot be blank");
        }
        try {
            float[] vector = delegate.embed(TextSegment.from(text)).content().vector();
            requireDimension(vector);
            return vector.clone();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Embedding provider request failed", failure);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private void requireDimension(float[] vector) {
        if (vector.length != dimension) {
            throw new IllegalStateException(
                    "Embedding provider returned dimension " + vector.length
                            + "; expected " + dimension);
        }
    }
}
