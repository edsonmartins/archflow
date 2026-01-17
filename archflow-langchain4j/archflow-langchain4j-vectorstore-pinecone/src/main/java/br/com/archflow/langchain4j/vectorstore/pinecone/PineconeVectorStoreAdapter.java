package br.com.archflow.langchain4j.vectorstore.pinecone;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
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
 * Suporta filtros por metadados e remoção de embeddings.
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
public class PineconeVectorStoreAdapter implements LangChainAdapter, dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>, AutoCloseable {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CloseableHttpClient httpClient;
    private String apiKey;
    private String apiUrl;
    private String indexName;
    private int vectorDimension;
    private Map<String, Object> config;

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

            Filter filter = request.filter();
            if (filter != null) {
                requestBody.put("filter", buildFilterCondition(filter));
            }

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
                            float[] vector = values.stream().map(Double::floatValue)
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
     * Builds a Pinecone filter condition from a LangChain4j Filter.
     *
     * <p>Pinecone filter format uses metadata filtering with operators:
     * <ul>
     *   <li>$eq - Equals</li>
     *   <li>$ne - Not equals</li>
     *   <li>$gt - Greater than</li>
     *   <li>$gte - Greater than or equal</li>
     *   <li>$lt - Less than</li>
     *   <li>$lte - Less than or equal</li>
     *   <li>$in - In array</li>
     *   <li>$and - Logical AND</li>
     *   <li>$or - Logical OR</li>
     *   <li>$not - Logical NOT</li>
     * </ul>
     *
     * @param filter The filter to convert
     * @return Pinecone filter map
     */
    private Map<String, Object> buildFilterCondition(Filter filter) {
        if (filter == null) {
            return Collections.emptyMap();
        }

        // Comparison filters
        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsEqualTo) {
            dev.langchain4j.store.embedding.filter.comparison.IsEqualTo eq =
                    (dev.langchain4j.store.embedding.filter.comparison.IsEqualTo) filter;
            return Collections.singletonMap(eq.key(), Collections.singletonMap("$eq", eq.comparisonValue()));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo) {
            dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo ne =
                    (dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo) filter;
            return Collections.singletonMap(ne.key(), Collections.singletonMap("$ne", ne.comparisonValue()));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan) {
            dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan gt =
                    (dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan) filter;
            return Collections.singletonMap(gt.key(), Collections.singletonMap("$gt", gt.comparisonValue()));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsLessThan) {
            dev.langchain4j.store.embedding.filter.comparison.IsLessThan lt =
                    (dev.langchain4j.store.embedding.filter.comparison.IsLessThan) filter;
            return Collections.singletonMap(lt.key(), Collections.singletonMap("$lt", lt.comparisonValue()));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo) {
            dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo gte =
                    (dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo) filter;
            return Collections.singletonMap(gte.key(), Collections.singletonMap("$gte", gte.comparisonValue()));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo) {
            dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo lte =
                    (dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo) filter;
            return Collections.singletonMap(lte.key(), Collections.singletonMap("$lte", lte.comparisonValue()));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.comparison.IsIn) {
            dev.langchain4j.store.embedding.filter.comparison.IsIn isIn =
                    (dev.langchain4j.store.embedding.filter.comparison.IsIn) filter;
            return Collections.singletonMap(isIn.key(), Collections.singletonMap("$in", isIn.comparisonValues()));
        }

        // Logical filters (binary AND/OR)
        if (filter instanceof dev.langchain4j.store.embedding.filter.logical.And) {
            dev.langchain4j.store.embedding.filter.logical.And and =
                    (dev.langchain4j.store.embedding.filter.logical.And) filter;
            Map<String, Object> left = buildFilterCondition(and.left());
            Map<String, Object> right = buildFilterCondition(and.right());
            return Map.of("$and", List.of(left, right));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.logical.Or) {
            dev.langchain4j.store.embedding.filter.logical.Or or =
                    (dev.langchain4j.store.embedding.filter.logical.Or) filter;
            Map<String, Object> left = buildFilterCondition(or.left());
            Map<String, Object> right = buildFilterCondition(or.right());
            return Map.of("$or", List.of(left, right));
        }

        if (filter instanceof dev.langchain4j.store.embedding.filter.logical.Not) {
            dev.langchain4j.store.embedding.filter.logical.Not not =
                    (dev.langchain4j.store.embedding.filter.logical.Not) filter;
            return Collections.singletonMap("$not", Collections.singletonList(buildFilterCondition(not.expression())));
        }

        throw new UnsupportedOperationException(
                "Unsupported filter type: " + filter.getClass().getSimpleName() +
                ". Supported types: IsEqualTo, IsNotEqualTo, IsGreaterThan, IsLessThan, " +
                "IsGreaterThanOrEqualTo, IsLessThanOrEqualTo, IsIn, And, Or, Not");
    }

    // Métodos de remoção
    public void remove(String id) {
        try {
            HttpDelete delete = new HttpDelete(apiUrl + "/vectors/delete?id=" + id + "&namespace=" + indexName);
            delete.setHeader("Api-Key", apiKey);

            try (CloseableHttpResponse response = httpClient.execute(delete)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new RuntimeException("Failed to remove vector from Pinecone: " + EntityUtils.toString(response.getEntity()));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error removing embedding from Pinecone", e);
        }
    }

    public void removeAll() {
        try {
            HttpDelete delete = new HttpDelete(apiUrl + "/vectors/delete?deleteAll=true&namespace=" + indexName);
            delete.setHeader("Api-Key", apiKey);

            try (CloseableHttpResponse response = httpClient.execute(delete)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new RuntimeException("Failed to remove all vectors from Pinecone: " + EntityUtils.toString(response.getEntity()));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error removing all embeddings from Pinecone", e);
        }
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (httpClient == null) {
            throw new IllegalStateException("Vector store not configured. Call configure() first.");
        }

        switch (operation) {
            case "search":
                if (!(input instanceof EmbeddingSearchRequest)) {
                    throw new IllegalArgumentException("Input must be an EmbeddingSearchRequest for search operation");
                }
                return search((EmbeddingSearchRequest) input);
            case "remove":
                if (!(input instanceof String)) {
                    throw new IllegalArgumentException("Input must be a String ID for remove operation");
                }
                remove((String) input);
                return null;
            case "removeAll":
                if (input != null) {
                    throw new IllegalArgumentException("Input must be null for removeAll operation (Pinecone limitation)");
                }
                removeAll();
                return null;
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }

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


}