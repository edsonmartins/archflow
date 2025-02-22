package br.com.archflow.langchain4j.chain.rag;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

/**
 * Factory para criação do RAG Chain Adapter.
 * Registrada via SPI para descoberta automática.
 */
public class RagChainAdapterFactory implements LangChainAdapterFactory {
    @Override
    public String getProvider() {
        return "rag";
    }

    @Override
    public LangChainAdapter createAdapter(Map<String, Object> properties) {
        RagChainAdapter adapter = new RagChainAdapter();
        adapter.configure(properties);
        return adapter;
    }

    @Override
    public boolean supports(String type) {
        return "chain".equals(type);
    }
}