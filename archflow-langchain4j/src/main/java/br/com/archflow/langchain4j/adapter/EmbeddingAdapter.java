package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.huggingface.HuggingFaceEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;
import java.util.Map;

// Embedding Adapter
public class EmbeddingAdapter implements LangChainAdapter {
    private EmbeddingModel embeddingModel;
    private EmbeddingStore<?> embeddingStore;

    @Override
    public void configure(Map<String, Object> properties) {
        String provider = properties.get("provider").toString();
        embeddingModel = switch(provider) {
            case "openai" -> OpenAiEmbeddingModel.builder()
                .apiKey(properties.get("apiKey").toString())
                .modelName(properties.get("model").toString())
                .build();
            case "huggingface" -> HuggingFaceEmbeddingModel.builder()
                .apiKey(properties.get("apiKey").toString())
                .modelId(properties.get("modelId").toString())
                .build();
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        return switch (operation) {
            case "embed" -> embeddingModel.embed(input.toString());
            case "embedBatch" -> embeddingModel.embedAll((List<String>) input);
            case "store" -> embeddingStore.add((Embedding) input);
            case "search" -> embeddingStore.findRelevant((Embedding) input, 
                                                        (int) context.get("limit").orElse(10));
            default -> throw new IllegalArgumentException("Invalid operation: " + operation);
        };
    }
}