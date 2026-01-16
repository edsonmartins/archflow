package br.com.archflow.langchain4j.provider;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for an LLM provider connection.
 *
 * <p>This class encapsulates all configuration needed to connect to a specific
 * LLM provider, including credentials, model selection, and generation parameters.
 *
 * <p>Usage example:
 * <pre>{@code
 * LLMProviderConfig config = LLMProviderConfig.builder()
 *     .provider(LLMProvider.OPENAI)
 *     .modelId("gpt-4o")
 *     .apiKey("sk-...")
 *     .temperature(0.7)
 *     .maxTokens(2048)
 *     .build();
 * }</pre>
 */
public class LLMProviderConfig {

    private final LLMProvider provider;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final Integer timeoutSeconds;
    private final Map<String, Object> extraParams;

    private LLMProviderConfig(Builder builder) {
        this.provider = builder.provider;
        this.modelId = builder.modelId;
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.extraParams = Map.copyOf(builder.extraParams);
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder with default values for the given provider.
     */
    public static Builder builder(LLMProvider provider) {
        Builder builder = new Builder();
        builder.provider = provider;
        if (!provider.getModels().isEmpty()) {
            builder.modelId = provider.getModels().get(0).id();
        }
        return builder;
    }

    public LLMProvider getProvider() {
        return provider;
    }

    public String getModelId() {
        return modelId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Duration getTimeout() {
        return timeoutSeconds != null ? Duration.ofSeconds(timeoutSeconds) : Duration.ofMinutes(5);
    }

    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    /**
     * Gets an extra parameter by key.
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtraParam(String key, Class<T> type) {
        Object value = extraParams.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Gets an extra parameter by key with default value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtraParam(String key, Class<T> type, T defaultValue) {
        Object value = extraParams.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return defaultValue;
    }

    /**
     * Validates this configuration.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (provider == null) {
            throw new IllegalArgumentException("Provider is required");
        }

        if (provider.requiresApiKey() && (apiKey == null || apiKey.trim().isEmpty())) {
            throw new IllegalArgumentException("API key is required for " + provider.getDisplayName());
        }

        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("Model ID is required");
        }

        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
        }

        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw new IllegalArgumentException("topP must be between 0.0 and 1.0");
        }

        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    /**
     * Creates a copy of this config with the given changes.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMProviderConfig that = (LLMProviderConfig) o;
        return provider == that.provider &&
                Objects.equals(modelId, that.modelId) &&
                Objects.equals(apiKey, that.apiKey) &&
                Objects.equals(baseUrl, that.baseUrl) &&
                Objects.equals(temperature, that.temperature) &&
                Objects.equals(topP, that.topP) &&
                Objects.equals(maxTokens, that.maxTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, modelId, apiKey, baseUrl, temperature, topP, maxTokens);
    }

    @Override
    public String toString() {
        return "LLMProviderConfig{" +
                "provider=" + provider +
                ", modelId='" + modelId + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", temperature=" + temperature +
                ", topP=" + topP +
                ", maxTokens=" + maxTokens +
                '}';
    }

    /**
     * Builder for LLMProviderConfig.
     */
    public static class Builder {
        private LLMProvider provider;
        private String modelId;
        private String apiKey;
        private String baseUrl;
        private Double temperature = 0.7;
        private Double topP;
        private Integer maxTokens;
        private Integer timeoutSeconds;
        private Map<String, Object> extraParams = new HashMap<>();

        private Builder() {
        }

        private Builder(LLMProviderConfig existing) {
            this.provider = existing.provider;
            this.modelId = existing.modelId;
            this.apiKey = existing.apiKey;
            this.baseUrl = existing.baseUrl;
            this.temperature = existing.temperature;
            this.topP = existing.topP;
            this.maxTokens = existing.maxTokens;
            this.timeoutSeconds = existing.timeoutSeconds;
            this.extraParams = new HashMap<>(existing.extraParams);
        }

        public Builder provider(LLMProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder provider(String providerId) {
            LLMProvider.fromId(providerId).ifPresent(this::provider);
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeoutSeconds = (int) timeout.getSeconds();
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder extraParam(String key, Object value) {
            this.extraParams.put(key, value);
            return this;
        }

        public Builder extraParams(Map<String, Object> params) {
            if (params != null) {
                this.extraParams.putAll(params);
            }
            return this;
        }

        /**
         * Sets Azure-specific parameters.
         */
        public Builder azure(String endpoint, String deploymentId, String apiKey) {
            this.baseUrl = endpoint;
            this.extraParam("azure.deploymentId", deploymentId);
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets Bedrock-specific parameters.
         */
        public Builder bedrock(String region, String accessKey, String secretKey) {
            this.extraParam("aws.region", region);
            this.extraParam("aws.accessKeyId", accessKey);
            this.extraParam("aws.secretKeyId", secretKey);
            return this;
        }

        /**
         * Sets Vertex AI-specific parameters.
         */
        public Builder vertexAi(String project, String location, String apiKey) {
            this.extraParam("vertex.project", project);
            this.extraParam("vertex.location", location);
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets Watsonx-specific parameters.
         */
        public Builder watsonx(String projectId, String apiKey, String endpoint) {
            this.extraParam("watsonx.projectId", projectId);
            this.apiKey = apiKey;
            this.baseUrl = endpoint;
            return this;
        }

        /**
         * Sets Ollama-specific parameters.
         */
        public Builder ollama(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public LLMProviderConfig build() {
            return new LLMProviderConfig(this);
        }
    }
}
