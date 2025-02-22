package br.com.archflow.langchain4j.embedding.local;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

public class LocalEmbeddingAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "local";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        LocalEmbeddingAdapter adapter = new LocalEmbeddingAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "embedding".equals(type);
    }
}