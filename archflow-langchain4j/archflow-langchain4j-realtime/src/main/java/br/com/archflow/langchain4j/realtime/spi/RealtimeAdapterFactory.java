package br.com.archflow.langchain4j.realtime.spi;

import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

/**
 * Service-loader factory for {@link RealtimeAdapter} implementations.
 *
 * <p>Providers register themselves via the standard Java SPI mechanism by
 * adding their fully-qualified factory class name to
 * {@code META-INF/services/br.com.archflow.langchain4j.realtime.spi.RealtimeAdapterFactory}.
 *
 * <p>Typical usage:
 * <pre>{@code
 * RealtimeAdapter adapter = RealtimeAdapterFactory
 *     .findByProvider("openai")
 *     .orElseThrow()
 *     .create(Map.of("api.key", "sk-..."));
 * }</pre>
 */
public interface RealtimeAdapterFactory {

    /**
     * Provider identifier this factory handles (e.g. {@code "openai"}).
     */
    String providerId();

    /**
     * Create and configure a new adapter instance.
     */
    RealtimeAdapter create(Map<String, Object> properties);

    // ── Discovery helpers ──────────────────────────────────────────

    static Iterable<RealtimeAdapterFactory> discover() {
        return ServiceLoader.load(RealtimeAdapterFactory.class);
    }

    static Optional<RealtimeAdapterFactory> findByProvider(String providerId) {
        return StreamSupport.stream(discover().spliterator(), false)
                .filter(f -> f.providerId().equalsIgnoreCase(providerId))
                .findFirst();
    }
}
