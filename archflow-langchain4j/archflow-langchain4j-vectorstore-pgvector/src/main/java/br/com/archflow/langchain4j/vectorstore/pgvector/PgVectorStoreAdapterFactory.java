package br.com.archflow.langchain4j.vectorstore.pgvector;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

public class PgVectorStoreAdapterFactory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "pgvector";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            PgVectorStoreAdapter adapter = new PgVectorStoreAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "vectorstore".equals(type);
        }
    }