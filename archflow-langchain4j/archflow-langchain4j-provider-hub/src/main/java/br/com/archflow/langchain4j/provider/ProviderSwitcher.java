package br.com.archflow.langchain4j.provider;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Utility for seamless runtime switching between LLM providers.
 *
 * <p>The ProviderSwitcher enables:
 * <ul>
 *   <li><b>A/B Testing:</b> Compare responses from different providers</li>
 *   <li><b>Fallback:</b> Automatic failover to backup providers</li>
 *   <li><b>Load balancing:</b> Distribute requests across providers</li>
 *   <li><b>Cost optimization:</b> Route to cheaper providers when appropriate</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * ProviderSwitcher switcher = new ProviderSwitcher("my-switcher")
 *     .primary(LLMProviderConfig.builder()
 *         .provider(LLMProvider.OPENAI)
 *         .modelId("gpt-4o")
 *         .apiKey("sk-...")
 *         .build())
 *     .fallback(LLMProviderConfig.builder()
 *         .provider(LLMProvider.ANTHROPIC)
 *         .modelId("claude-3-5-sonnet-20241022")
 *         .apiKey("sk-ant-...")
 *         .build())
 *     .build();
 *
 * // Will try primary first, fallback on failure
 * String response = switcher.executeWithFallback(
 *     model -> model.generate("Hello!")
 * );
 * }</pre>
 */
public class ProviderSwitcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderSwitcher.class);

    private final String switcherId;
    private final LLMProviderConfig primaryConfig;
    private final LLMProviderConfig fallbackConfig;
    private final LLMProviderHub hub;
    private final SwitchStrategy strategy;
    private final Map<String, ProviderStats> stats;
    private final List<SwitchListener> listeners;

    private ProviderSwitcher(Builder builder) {
        this.switcherId = builder.switcherId;
        this.primaryConfig = builder.primary;
        this.fallbackConfig = builder.fallback;
        this.hub = builder.hub != null ? builder.hub : LLMProviderHub.getInstance();
        this.strategy = builder.strategy != null ? builder.strategy : new PrimaryOnlyStrategy();
        this.stats = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>(builder.listeners);

        // Register configs with hub
        hub.registerConfig(switcherId + ":primary", primaryConfig);
        if (fallbackConfig != null) {
            hub.registerConfig(switcherId + ":fallback", fallbackConfig);
        }

        // Initialize stats
        stats.put("primary", new ProviderStats(primaryConfig.getProvider().getId()));
        if (fallbackConfig != null) {
            stats.put("fallback", new ProviderStats(fallbackConfig.getProvider().getId()));
        }
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder(String switcherId) {
        return new Builder(switcherId);
    }

    // ========== Execution ==========

    /**
     * Executes an operation with automatic fallback on failure.
     *
     * @param operation The operation to execute
     * @param <T> The result type
     * @return The result of the operation
     * @throws ProviderExhaustedException if all providers fail
     */
    public <T> T executeWithFallback(Function<ChatModel, T> operation) {
        return executeWithFallback(operation, null);
    }

    /**
     * Executes an operation with automatic fallback on failure.
     *
     * @param operation The operation to execute
     * @param context Optional context for logging
     * @param <T> The result type
     * @return The result of the operation
     * @throws ProviderExhaustedException if all providers fail
     */
    public <T> T executeWithFallback(
            Function<ChatModel, T> operation,
            String context) {

        List<String> providerOrder = strategy.selectProvider(stats);

        Exception lastException = null;

        for (String providerKey : providerOrder) {
            if (!stats.containsKey(providerKey)) {
                continue;
            }

            ProviderStats providerStats = stats.get(providerKey);
            String configId = switcherId + ":" + providerKey;

            try {
                long startTime = System.currentTimeMillis();
                ChatModel model = hub.getModel(configId);
                T result = operation.apply(model);
                long duration = System.currentTimeMillis() - startTime;

                providerStats.recordSuccess(duration);
                notifySuccess(providerKey, context, duration);
                return result;

            } catch (Exception e) {
                lastException = e;
                providerStats.recordFailure();
                notifyFailure(providerKey, context, e);

                if (providerKey.equals("primary") && fallbackConfig != null) {
                    log.warn("Primary provider failed for switcher '{}', trying fallback: {}",
                            switcherId, e.getMessage());
                }
            }
        }

        throw new ProviderExhaustedException(
                "All providers exhausted for switcher: " + switcherId, lastException);
    }

    /**
     * Executes an operation with a specific provider.
     *
     * @param providerKey "primary" or "fallback"
     * @param operation The operation to execute
     * @param <T> The result type
     * @return The result of the operation
     */
    public <T> T executeWith(String providerKey,
                             Function<ChatModel, T> operation) {

        if (!stats.containsKey(providerKey)) {
            throw new IllegalArgumentException("Unknown provider key: " + providerKey);
        }

        String configId = switcherId + ":" + providerKey;
        ChatModel model = hub.getModel(configId);

        return operation.apply(model);
    }

    /**
     * Executes an operation comparing results from all providers.
     *
     * @param operation The operation to execute
     * @param <T> The result type
     * @return A map of provider key to result
     */
    public <T> Map<String, T> compare(Function<ChatModel, T> operation) {
        Map<String, T> results = new HashMap<>();

        for (String providerKey : stats.keySet()) {
            try {
                String configId = switcherId + ":" + providerKey;
                ChatModel model = hub.getModel(configId);
                T result = operation.apply(model);
                results.put(providerKey, result);
            } catch (Exception e) {
                log.error("Error executing on provider '{}'", providerKey, e);
            }
        }

        return results;
    }

    // ========== Stats ==========

    /**
     * Gets statistics for all providers.
     */
    public Map<String, ProviderStats> getStats() {
        return Map.copyOf(stats);
    }

    /**
     * Gets statistics for a specific provider.
     */
    public ProviderStats getStats(String providerKey) {
        return stats.get(providerKey);
    }

    /**
     * Resets statistics for all providers.
     */
    public void resetStats() {
        stats.values().forEach(ProviderStats::reset);
    }

    // ========== Configuration ==========

    /**
     * Gets the primary configuration.
     */
    public LLMProviderConfig getPrimaryConfig() {
        return primaryConfig;
    }

    /**
     * Gets the fallback configuration.
     */
    public Optional<LLMProviderConfig> getFallbackConfig() {
        return Optional.ofNullable(fallbackConfig);
    }

    /**
     * Updates the primary configuration at runtime.
     */
    public void updatePrimary(LLMProviderConfig newConfig) {
        hub.registerConfig(switcherId + ":primary", newConfig);
        stats.put("primary", new ProviderStats(newConfig.getProvider().getId()));
        log.info("Updated primary config for switcher '{}'", switcherId);
    }

    /**
     * Updates the fallback configuration at runtime.
     */
    public void updateFallback(LLMProviderConfig newConfig) {
        if (fallbackConfig == null) {
            throw new IllegalStateException("No fallback config configured");
        }
        hub.registerConfig(switcherId + ":fallback", newConfig);
        stats.put("fallback", new ProviderStats(newConfig.getProvider().getId()));
        log.info("Updated fallback config for switcher '{}'", switcherId);
    }

    // ========== Listeners ==========

    public void addListener(SwitchListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SwitchListener listener) {
        listeners.remove(listener);
    }

    private void notifySuccess(String providerKey, String context, long duration) {
        for (SwitchListener listener : listeners) {
            try {
                listener.onSuccess(switcherId, providerKey, context, duration);
            } catch (Exception e) {
                log.error("Error notifying listener", e);
            }
        }
    }

    private void notifyFailure(String providerKey, String context, Exception error) {
        for (SwitchListener listener : listeners) {
            try {
                listener.onFailure(switcherId, providerKey, context, error);
            } catch (Exception e) {
                log.error("Error notifying listener", e);
            }
        }
    }

    // ========== Builder ==========

    public static class Builder {
        private final String switcherId;
        private LLMProviderConfig primary;
        private LLMProviderConfig fallback;
        private LLMProviderHub hub;
        private SwitchStrategy strategy;
        private final List<SwitchListener> listeners = new ArrayList<>();

        private Builder(String switcherId) {
            this.switcherId = switcherId;
        }

        public Builder primary(LLMProviderConfig config) {
            this.primary = config;
            return this;
        }

        public Builder fallback(LLMProviderConfig config) {
            this.fallback = config;
            return this;
        }

        public Builder hub(LLMProviderHub hub) {
            this.hub = hub;
            return this;
        }

        public Builder strategy(SwitchStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder addListener(SwitchListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public ProviderSwitcher build() {
            if (primary == null) {
                throw new IllegalArgumentException("Primary config is required");
            }
            return new ProviderSwitcher(this);
        }
    }

    // ========== Inner Classes ==========

    /**
     * Statistics for a provider.
     */
    public static class ProviderStats {
        private final String providerId;
        private volatile int successCount;
        private volatile int failureCount;
        private volatile long totalDuration;
        private volatile long minDuration = Long.MAX_VALUE;
        private volatile long maxDuration;

        public ProviderStats(String providerId) {
            this.providerId = providerId;
        }

        public String getProviderId() {
            return providerId;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public int getTotalCount() {
            return successCount + failureCount;
        }

        public double getSuccessRate() {
            int total = getTotalCount();
            return total > 0 ? (double) successCount / total : 0.0;
        }

        public long getAverageDuration() {
            return successCount > 0 ? totalDuration / successCount : 0;
        }

        public long getMinDuration() {
            return minDuration == Long.MAX_VALUE ? 0 : minDuration;
        }

        public long getMaxDuration() {
            return maxDuration;
        }

        private void recordSuccess(long duration) {
            successCount++;
            totalDuration += duration;
            if (duration < minDuration) {
                minDuration = duration;
            }
            if (duration > maxDuration) {
                maxDuration = duration;
            }
        }

        private void recordFailure() {
            failureCount++;
        }

        private void reset() {
            successCount = 0;
            failureCount = 0;
            totalDuration = 0;
            minDuration = Long.MAX_VALUE;
            maxDuration = 0;
        }
    }

    /**
     * Strategy for selecting which provider to use.
     */
    public interface SwitchStrategy {
        List<String> selectProvider(Map<String, ProviderStats> stats);
    }

    /**
     * Strategy that always uses primary provider first.
     */
    public static class PrimaryOnlyStrategy implements SwitchStrategy {
        @Override
        public List<String> selectProvider(Map<String, ProviderStats> stats) {
            List<String> order = new ArrayList<>();
            if (stats.containsKey("primary")) {
                order.add("primary");
            }
            if (stats.containsKey("fallback")) {
                order.add("fallback");
            }
            return order;
        }
    }

    /**
     * Strategy that selects based on success rate.
     */
    public static class SuccessRateStrategy implements SwitchStrategy {
        @Override
        public List<String> selectProvider(Map<String, ProviderStats> stats) {
            List<String> keys = new ArrayList<>(stats.keySet());
            keys.sort((a, b) -> {
                double rateA = stats.get(a).getSuccessRate();
                double rateB = stats.get(b).getSuccessRate();
                return Double.compare(rateB, rateA); // Descending
            });
            return keys;
        }
    }

    /**
     * Strategy that selects based on latency.
     */
    public static class LowestLatencyStrategy implements SwitchStrategy {
        @Override
        public List<String> selectProvider(Map<String, ProviderStats> stats) {
            List<String> keys = new ArrayList<>(stats.keySet());
            keys.sort((a, b) -> {
                long avgA = stats.get(a).getAverageDuration();
                long avgB = stats.get(b).getAverageDuration();
                if (avgA == 0 && avgB == 0) return 0;
                if (avgA == 0) return 1;
                if (avgB == 0) return -1;
                return Long.compare(avgA, avgB); // Ascending
            });
            return keys;
        }
    }

    /**
     * Listener for switch events.
     */
    public interface SwitchListener {
        void onSuccess(String switcherId, String providerKey, String context, long duration);
        void onFailure(String switcherId, String providerKey, String context, Exception error);
    }

    /**
     * Exception thrown when all providers are exhausted.
     */
    public static class ProviderExhaustedException extends RuntimeException {
        public ProviderExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
