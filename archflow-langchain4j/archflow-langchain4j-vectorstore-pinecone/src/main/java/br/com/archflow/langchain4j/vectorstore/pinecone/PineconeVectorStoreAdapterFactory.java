package br.com.archflow.langchain4j.vectorstore.pinecone;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;

import java.util.Map;

public class PineconeVectorStoreAdapterFactory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "pinecone";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            PineconeVectorStoreAdapter adapter = new PineconeVectorStoreAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "vectorstore".equals(type);
        }
    }