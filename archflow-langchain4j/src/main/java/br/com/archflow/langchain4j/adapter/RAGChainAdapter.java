package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.retriever.EmbeddingStoreRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.Map;

// RAG Chain Adapter
public class RAGChainAdapter implements LangChainAdapter {
    private RetrievalAugmentedChain chain;
    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;
    private ChatLanguageModel chatModel;

    @Override
    public void configure(Map<String, Object> properties) {
        // Configure embedding model and store
        configureEmbeddings(properties);
        
        // Configure chat model
        configureChatModel(properties);
        
        // Build RAG chain
        chain = RetrievalAugmentedChain.builder()
            .chatLanguageModel(chatModel)
            .retrievalAugmentor(EmbeddingStoreRetriever.from(
                embeddingStore,
                embeddingModel))
            .build();
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        return switch (operation) {
            case "query" -> chain.execute(input.toString());
            case "streamingQuery" -> chain.streamingExecute(input.toString());
            default -> throw new IllegalArgumentException("Invalid operation: " + operation);
        };
    }
}