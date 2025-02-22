package br.com.archflow.langchain4j.embedding.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para criação do OpenAI Embedding Adapter.
 * Registrada via SPI para descoberta automática.
 */
public class OpenAiEmbeddingAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "openai";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        OpenAiEmbeddingAdapter adapter = new OpenAiEmbeddingAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "embedding".equals(type);
    }
}