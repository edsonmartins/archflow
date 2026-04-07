package br.com.archflow.brainsentry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

/**
 * HTTP client for Brain Sentry REST API.
 *
 * <p>Uses Java 17 built-in HttpClient — zero external HTTP dependencies.
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * var client = new BrainSentryClient(BrainSentryConfig.of("http://localhost:8081/api"));
 *
 * // Intercept and enrich a prompt
 * EnrichedPrompt enriched = client.intercept("How do we handle auth?", 2000);
 *
 * // Store a memory
 * Memory mem = client.createMemory("Use JWT tokens", "DECISION", "CRITICAL", "PROCEDURAL", List.of("auth"));
 *
 * // Search memories
 * List<Memory> results = client.searchMemories("authentication", 5);
 * }</pre>
 */
public class BrainSentryClient {

    private static final Logger log = LoggerFactory.getLogger(BrainSentryClient.class);

    private final BrainSentryConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public BrainSentryClient(BrainSentryConfig config) {
        this.config = Objects.requireNonNull(config);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Intercepts a prompt, enriching it with relevant memories.
     */
    public EnrichedPrompt intercept(String prompt, int maxTokens) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("maxTokens", maxTokens > 0 ? maxTokens : config.maxTokenBudget());
        body.put("forceDeepAnalysis", config.deepAnalysisEnabled());
        if (config.tenantId() != null) {
            body.put("context", Map.of("tenantId", config.tenantId()));
        }

        HttpResponse<String> response = post("/v1/intercept", body);
        if (response.statusCode() != 200) {
            log.warn("Intercept failed ({}): {}", response.statusCode(), response.body());
            return new EnrichedPrompt(false, prompt, null, List.of(), 0);
        }

        Map<String, Object> json = mapper.readValue(response.body(), new TypeReference<>() {});
        boolean enhanced = Boolean.TRUE.equals(json.get("enhanced"));
        String injected = (String) json.get("injectedContext");
        @SuppressWarnings("unchecked")
        List<String> memoriesUsed = (List<String>) json.getOrDefault("memoriesUsed", List.of());
        long latency = json.containsKey("latencyMs") ? ((Number) json.get("latencyMs")).longValue() : 0;

        return new EnrichedPrompt(enhanced, prompt, injected, memoriesUsed, latency);
    }

    /**
     * Creates a new memory in Brain Sentry.
     */
    public Memory createMemory(String content, String category, String importance,
                                String memoryType, List<String> tags) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("category", category);
        body.put("importance", importance);
        body.put("memoryType", memoryType);
        body.put("tags", tags);
        body.put("sourceType", "archflow");
        if (config.tenantId() != null) body.put("tenantId", config.tenantId());

        HttpResponse<String> response = post("/v1/memories", body);
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Failed to create memory: " + response.statusCode() + " " + response.body());
        }

        return parseMemory(mapper.readValue(response.body(), new TypeReference<>() {}));
    }

    /**
     * Searches memories using hybrid search (vector + keyword + graph).
     */
    public List<Memory> searchMemories(String query, int limit) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("query", query, "limit", limit);

        HttpResponse<String> response = post("/v1/memories/search", body);
        if (response.statusCode() != 200) {
            log.warn("Search failed ({}): {}", response.statusCode(), response.body());
            return List.of();
        }

        List<Map<String, Object>> results = mapper.readValue(response.body(), new TypeReference<>() {});
        return results.stream().map(this::parseMemory).toList();
    }

    /**
     * Gets a memory by ID.
     */
    public Optional<Memory> getMemory(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = get("/v1/memories/" + id);
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get memory: " + response.statusCode());
        }

        Map<String, Object> json = mapper.readValue(response.body(), new TypeReference<>() {});
        return Optional.of(parseMemory(json));
    }

    /**
     * Checks if Brain Sentry is healthy.
     */
    public boolean isHealthy() {
        try {
            HttpResponse<String> response = get("/health");
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // --- HTTP helpers ---

    private HttpResponse<String> post(String path, Object body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .header("Content-Type", "application/json")
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (config.apiKey() != null) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .timeout(config.timeout())
                .GET();

        if (config.apiKey() != null) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Memory parseMemory(Map<String, Object> json) {
        return new Memory(
                (String) json.get("id"),
                (String) json.get("content"),
                (String) json.get("summary"),
                (String) json.get("category"),
                (String) json.get("importance"),
                (String) json.get("memoryType"),
                (List<String>) json.getOrDefault("tags", List.of()),
                (Map<String, Object>) json.getOrDefault("metadata", Map.of()),
                json.containsKey("createdAt") ? Instant.parse((String) json.get("createdAt")) : Instant.now()
        );
    }
}
