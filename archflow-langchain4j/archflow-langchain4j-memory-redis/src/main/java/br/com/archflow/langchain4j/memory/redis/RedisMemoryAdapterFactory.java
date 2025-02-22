package br.com.archflow.langchain4j.memory.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para o adapter Redis
 */
public class RedisMemoryAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "redis";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        RedisMemoryAdapter adapter = new RedisMemoryAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "memory".equals(type);
    }
}