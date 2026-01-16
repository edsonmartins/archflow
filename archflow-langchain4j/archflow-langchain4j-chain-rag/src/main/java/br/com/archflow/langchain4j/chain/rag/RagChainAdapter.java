package br.com.archflow.langchain4j.chain.rag;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.chain.ConversationalChain;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Adapter para Retrieval Augmented Generation (RAG) Chain do LangChain4j.
 * Esta implementação permite combinar diferentes providers para embeddings, vector store e modelos de linguagem.
 *
 * <p>O RAG combina três componentes principais:
 * <ul>
 *   <li>Embedding Model - para vetorização de texto</li>
 *   <li>Vector Store - para armazenamento e busca de embeddings</li>
 *   <li>Language Model - para geração de respostas</li>
 * </ul>
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "embedding.provider", "openai",           // Provider para embeddings
 *     "vectorstore.provider", "redis",          // Provider para vector store
 *     "languagemodel.provider", "anthropic",    // Provider para modelo de linguagem
 *     "retriever.maxResults", 2,                // Máximo de resultados por busca
 *     "retriever.minScore", 0.7                 // Score mínimo de similaridade
 * );
 * }</pre>
 *
 * <p>Providers suportados dependem dos adapters disponíveis no classpath e são descobertos via SPI.
 *
 * @see LangChainAdapter
 * @see LangChainRegistry
 */
public class RagChainAdapter implements LangChainAdapter {
    private volatile ConversationalChain chain; // volatile para visibilidade em multi-threading
    private volatile EmbeddingModel embeddingModel;
    private volatile EmbeddingStore<TextSegment> embeddingStore;
    private Map<String, Object> config;

    /**
     * Valida as configurações fornecidas.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        validateEmbeddingConfig(properties);
        validateVectorStoreConfig(properties);
        validateLanguageModelConfig(properties);

        // Validação de parâmetros opcionais
        Object maxResults = properties.get("retriever.maxResults");
        if (maxResults != null) {
            if (!(maxResults instanceof Integer) || (Integer) maxResults < 1) {
                throw new IllegalArgumentException("retriever.maxResults must be a positive integer");
            }
        }

        Object minScore = properties.get("retriever.minScore");
        if (minScore != null) {
            if (!(minScore instanceof Number) || ((Number) minScore).doubleValue() < 0.0 || ((Number) minScore).doubleValue() > 1.0) {
                throw new IllegalArgumentException("retriever.minScore must be a number between 0.0 and 1.0");
            }
        }
    }

    private void validateEmbeddingConfig(Map<String, Object> properties) {
        String embeddingProvider = (String) properties.get("embedding.provider");
        if (embeddingProvider == null || embeddingProvider.trim().isEmpty()) {
            throw new IllegalArgumentException("Embedding provider is required");
        }
        if (!LangChainRegistry.hasProvider(embeddingProvider)) {
            throw new IllegalArgumentException("Unsupported embedding provider: " + embeddingProvider);
        }
    }

    private void validateVectorStoreConfig(Map<String, Object> properties) {
        String vectorStoreProvider = (String) properties.get("vectorstore.provider");
        if (vectorStoreProvider == null || vectorStoreProvider.trim().isEmpty()) {
            throw new IllegalArgumentException("Vector store provider is required");
        }
        if (!LangChainRegistry.hasProvider(vectorStoreProvider)) {
            throw new IllegalArgumentException("Unsupported vector store provider: " + vectorStoreProvider);
        }
    }

    private void validateLanguageModelConfig(Map<String, Object> properties) {
        String languageModelProvider = (String) properties.get("languagemodel.provider");
        if (languageModelProvider == null || languageModelProvider.trim().isEmpty()) {
            throw new IllegalArgumentException("Language model provider is required");
        }
        if (!LangChainRegistry.hasProvider(languageModelProvider)) {
            throw new IllegalArgumentException("Unsupported language model provider: " + languageModelProvider);
        }
    }

    /**
     * Configura o adapter com os providers especificados.
     *
     * <p>Requer as seguintes configurações:
     * <ul>
     *   <li>{@code embedding.provider} - Provider para modelo de embeddings</li>
     *   <li>{@code vectorstore.provider} - Provider para vector store</li>
     *   <li>{@code languagemodel.provider} - Provider para modelo de linguagem</li>
     * </ul>
     *
     * <p>Configurações opcionais:
     * <ul>
     *   <li>{@code retriever.maxResults} - Número máximo de resultados (default: 2)</li>
     *   <li>{@code retriever.minScore} - Score mínimo de similaridade (default: 0.7)</li>
     * </ul>
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se alguma configuração obrigatória estiver faltando
     */
    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        // Obtém embedding model
        LangChainAdapter embeddingAdapter = LangChainRegistry.createAdapter(
                (String) properties.get("embedding.provider"), "embedding", properties);
        if (!(embeddingAdapter instanceof EmbeddingModel)) {
            throw new IllegalStateException("Provider " + properties.get("embedding.provider") + " does not return an EmbeddingModel");
        }
        this.embeddingModel = (EmbeddingModel) embeddingAdapter;

        // Obtém vector store
        LangChainAdapter vectorStoreAdapter = LangChainRegistry.createAdapter(
                (String) properties.get("vectorstore.provider"), "vectorstore", properties);
        if (!(vectorStoreAdapter instanceof EmbeddingStore)) {
            throw new IllegalStateException("Provider " + properties.get("vectorstore.provider") + " does not return an EmbeddingStore");
        }
        this.embeddingStore = (EmbeddingStore<TextSegment>) vectorStoreAdapter;

        // Obtém language model
        LangChainAdapter languageModelAdapter = LangChainRegistry.createAdapter(
                (String) properties.get("languagemodel.provider"), "chat", properties);
        if (!(languageModelAdapter instanceof ChatModel)) {
            throw new IllegalStateException("Provider " + properties.get("languagemodel.provider") + " does not return a ChatModel");
        }
        ChatModel languageModel = (ChatModel) languageModelAdapter;

        // Configura a chain
        this.chain = ConversationalChain.builder()
                .chatModel(languageModel)
                .build();
    }

    /**
     * Executa operações no RAG Chain.
     *
     * <p>Operações suportadas:
     * <ul>
     *   <li>{@code query} - Executa uma consulta na base de conhecimento</li>
     *   <li>{@code addDocuments} - Adiciona documentos à base de conhecimento</li>
     * </ul>
     *
     * @param operation Nome da operação
     * @param input     Para "query": String com a pergunta
     *                  Para "addDocuments": List<TextSegment> com os documentos
     * @param context   Contexto de execução
     * @return Para "query": String com a resposta
     *         Para "addDocuments": null
     * @throws IllegalArgumentException se a operação for inválida
     * @throws IllegalStateException    se o adapter não estiver configurado
     */
    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (chain == null || embeddingModel == null || embeddingStore == null) {
            throw new IllegalStateException("Chain, embedding model, or embedding store not configured. Call configure() first.");
        }

        try {
            if ("query".equals(operation)) {
                if (!(input instanceof String)) {
                    throw new IllegalArgumentException("Input must be a string for query operation");
                }

                String query = (String) input;
                int maxResults = (Integer) config.getOrDefault("retriever.maxResults", 2);
                double minScore = (Double) config.getOrDefault("retriever.minScore", 0.7);

                // Gera embedding da query
                var queryEmbedding = embeddingModel.embed(TextSegment.from(query)).content();

                // Busca documentos similares
                var searchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .minScore(minScore)
                        .build();

                var searchResult = embeddingStore.search(searchRequest);
                List<EmbeddingMatch<TextSegment>> relevantDocs = searchResult.matches();

                // Constrói o prompt com os documentos relevantes
                String contextPrompt = relevantDocs.stream()
                        .map(match -> match.embedded().text())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");

                String augmentedPrompt = String.format("""
                        Based on the following context:
                        ---
                        %s
                        ---
                        Please answer the question: %s
                        """, contextPrompt, query);

                // Gera resposta
                return chain.execute(augmentedPrompt);
            }

            if ("addDocuments".equals(operation)) {
                if (!(input instanceof List<?>)) {
                    throw new IllegalArgumentException("Input must be a List<TextSegment> for addDocuments operation");
                }

                List<TextSegment> documents = (List<TextSegment>) input;
                var embeddings = embeddingModel.embedAll(documents).content();
                embeddingStore.addAll(embeddings, documents);
                return null;
            }

            throw new IllegalArgumentException("Unsupported operation: " + operation);
        } catch (Exception e) {
            throw new RuntimeException("Error executing operation: " + operation, e);
        }
    }

    /**
     * Libera recursos utilizados pelo adapter.
     */
    @Override
    public void shutdown() {
        this.chain = null;
        this.embeddingModel = null;
        if (embeddingStore instanceof AutoCloseable) {
            try {
                ((AutoCloseable) embeddingStore).close();
            } catch (Exception e) {
                // Logar o erro, se houver sistema de log
            }
        }
        this.embeddingStore = null;
        this.config = null;
    }

    public static class Factory implements LangChainAdapterFactory {
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
}