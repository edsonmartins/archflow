package br.com.archflow.langchain4j.vectorstore.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para criação do Redis Vector Store Adapter.
 * Registrada via SPI para descoberta automática.
 */
public class RedisVectorStoreAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "redis";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        RedisVectorStoreAdapter adapter = new RedisVectorStoreAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "vectorstore".equals(type);
    }
}