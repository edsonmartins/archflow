package br.com.archflow.langchain4j.vectorstore.pinecone;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter para armazenamento e busca de embeddings usando Pinecone.
 * Utiliza a API REST do Pinecone para gerenciar vetores em um serviço na nuvem.
 *
 * <p>Este adapter requer uma conta no Pinecone e um índice criado previamente.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "pinecone.apiKey", "sua-chave-api-pinecone",       // Chave de API do Pinecone
 *     "pinecone.apiUrl", "https://seu-indice.pinecone.io", // URL do índice Pinecone
 *     "pinecone.indexName", "embeddings",                // Nome do índice (opcional)
 *     "pinecone.dimension", 1536                         // Dimensão dos vetores
 * );
 * }</pre>
 */
public class PineconeVectorStoreAdapter implements LangChainAdapter, EmbeddingStore<TextSegment>, AutoCloseable {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CloseableHttpClient httpClient;
    private String apiKey;
    private String apiUrl;
    private String indexName;
    private int vectorDimension;
    private Map<String, Object> config;

    /**
     * Valida as configurações fornecidas para o adapter.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String apiKey = (String) properties.get("pinecone.apiKey");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Pinecone API key is required");
        }

        String apiUrl = (String) properties.get("pinecone.apiUrl");
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Pinecone API URL is required");
        }

        String indexName = (String) properties.get("pinecone.indexName");
        if (indexName != null && indexName.trim().isEmpty()) {
            throw new IllegalArgumentException("Pinecone index name cannot be empty if provided");
        }

        Object dimension = properties.get("pinecone.dimension");
        if (dimension == null || !(dimension instanceof Number) || ((Number) dimension).intValue() <= 0) {
            throw new IllegalArgumentException("Vector dimension is required and must be a positive number");
        }
    }

    /**
     * Configura o adapter com as propriedades especificadas.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        this.apiKey = (String) properties.get("pinecone.apiKey");
        this.apiUrl = (String) properties.get("pinecone.apiUrl");
        this.indexName = (String) properties.getOrDefault("pinecone.indexName", "embeddings");
        this.vectorDimension = (Integer) properties.get("pinecone.dimension");

        this.httpClient = HttpClients.createDefault();
    }

    @Override
    public void add(String id, Embedding embedding) {
        upsertVectors(Collections.singletonList(new VectorData(id, embedding.vector(), null)));
    }

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        upsertVectors(Collections.singletonList(new VectorData(id, embedding.vector(), embedded != null ? embedded.text() : null)));
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = generateIds(embeddings.size());
        addAll(ids, embeddings, null);
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        if (ids.size() != embeddings.size() || (embedded != null && embedded.size() != ids.size())) {
            throw new IllegalArgumentException("All lists must have the same size");
        }

        List<VectorData> vectors = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String text = embedded != null ? embedded.get(i).text() : null;
            vectors.add(new VectorData(ids.get(i), embeddings.get(i).vector(), text));
        }
        upsertVectors(vectors);
    }

    private void upsertVectors(List<VectorData> vectors) {
        try {
            HttpPost post = new HttpPost(apiUrl + "/vectors/upsert");
            post.setHeader("Api-Key", apiKey);
            post.setHeader("Content-Type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("vectors", vectors.stream().map(v -> {
                Map<String, Object> vector = new HashMap<>();
                vector.put("id", v.id);
                vector.put("values", v.values);
                if (v.text != null) {
                    vector.put("metadata", Collections.singletonMap("text", v.text));
                }
                return vector;
            }).collect(Collectors.toList()));
            requestBody.put("namespace", indexName);

            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody)));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new RuntimeException("Failed to upsert vectors to Pinecone: " + EntityUtils.toString(response.getEntity()));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error upserting vectors to Pinecone", e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        try {
            HttpPost post = new HttpPost(apiUrl + "/query");
            post.setHeader("Api-Key", apiKey);
            post.setHeader("Content-Type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("vector", request.queryEmbedding().vector());
            requestBody.put("topK", request.maxResults());
            requestBody.put("includeValues", true);
            requestBody.put("includeMetadata", true);
            requestBody.put("namespace", indexName);

            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody)));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new RuntimeException("Failed to search vectors in Pinecone: " + EntityUtils.toString(response.getEntity()));
                }

                String jsonResponse = EntityUtils.toString(response.getEntity());
                Map<String, Object> result = objectMapper.readValue(jsonResponse, Map.class);
                List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");

                List<EmbeddingMatch<TextSegment>> embeddingMatches = matches.stream()
                        .map(match -> {
                            String id = (String) match.get("id");
                            double score = (Double) match.get("score");
                            List<Double> values = (List<Double>) match.get("values");
                            // Correção: converter List<Double> diretamente para float[]
                            float[] vector = values.stream()
                                    .map(Double::floatValue)
                                    .collect(Collectors.collectingAndThen(Collectors.toList(), list -> {
                                        float[] array = new float[list.size()];
                                        for (int i = 0; i < list.size(); i++) {
                                            array[i] = list.get(i);
                                        }
                                        return array;
                                    }));
                            Map<String, Object> metadata = (Map<String, Object>) match.get("metadata");
                            String text = metadata != null ? (String) metadata.get("text") : null;

                            if (score >= request.minScore()) {
                                Embedding embedding = new Embedding(vector);
                                TextSegment textSegment = text != null ? TextSegment.from(text) : null;
                                return new EmbeddingMatch<>(score, id, embedding, textSegment);
                            }
                            return null;
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                return new EmbeddingSearchResult<>(embeddingMatches);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error searching embeddings in Pinecone", e);
        }
    }

    /**
     * Executa operações no vector store do Pinecone.
     *
     * @param operation Nome da operação ("search" é suportado)
     * @param input     Para "search": {@link EmbeddingSearchRequest}
     * @param context   Contexto de execução (não utilizado atualmente)
     * @return Resultado da operação
     * @throws IllegalArgumentException se a operação for inválida
     * @throws IllegalStateException    se o adapter não estiver configurado
     */
    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (httpClient == null) {
            throw new IllegalStateException("Vector store not configured. Call configure() first.");
        }

        if ("search".equals(operation)) {
            if (!(input instanceof EmbeddingSearchRequest)) {
                throw new IllegalArgumentException("Input must be an EmbeddingSearchRequest for search operation");
            }
            return search((EmbeddingSearchRequest) input);
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    /**
     * Libera recursos utilizados pelo adapter, fechando o cliente HTTP.
     */
    @Override
    public void shutdown() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                // Logar o erro se houver sistema de log
            }
            httpClient = null;
        }
        this.config = null;
    }

    @Override
    public void close() {
        shutdown();
    }

    public List<String> generateIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    // Classe auxiliar para representar dados de vetores
    private static class VectorData {
        String id;
        float[] values;
        String text;

        VectorData(String id, float[] values, String text) {
            this.id = id;
            this.values = values;
            this.text = text;
        }
    }

    public static class Factory implements LangChainAdapterFactory {
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
}