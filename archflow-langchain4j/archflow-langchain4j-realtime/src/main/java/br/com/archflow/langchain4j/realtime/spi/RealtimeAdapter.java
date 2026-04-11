package br.com.archflow.langchain4j.realtime.spi;

import java.util.Map;

/**
 * SPI for realtime voice adapters.
 *
 * <p>Unlike the chat-oriented {@code LangChainAdapter}, realtime adapters
 * expose a session-oriented API: a session is opened once per
 * {@code (tenantId, personaId)} pair and stays alive until either side
 * closes it. Frames flow in both directions asynchronously.
 *
 * <p>Implementations are discovered via the Java {@link java.util.ServiceLoader}
 * using {@link RealtimeAdapterFactory}. A provider registers itself by
 * adding a line to
 * {@code META-INF/services/br.com.archflow.langchain4j.realtime.spi.RealtimeAdapterFactory}.
 *
 * <p>Thread-safety: a single {@code RealtimeAdapter} instance is shared
 * across tenants and MUST be thread-safe. Sessions returned by
 * {@link #openSession} are single-user.
 */
public interface RealtimeAdapter {

    /**
     * Provider identifier (e.g. {@code "openai"}, {@code "gemini"}).
     * Used by the controller layer to select an adapter when multiple
     * providers are registered at runtime.
     */
    String providerId();

    /**
     * Apply the adapter-wide configuration. Called once during bootstrap
     * with credentials, base URLs, model selection and any other knob
     * exposed by the provider.
     *
     * <p>Typical keys:
     * <ul>
     *   <li>{@code api.key}</li>
     *   <li>{@code base.url}</li>
     *   <li>{@code model} (e.g. {@code gpt-4o-realtime-preview})</li>
     *   <li>{@code voice} (e.g. {@code alloy})</li>
     *   <li>{@code instructions} (system prompt applied to every session)</li>
     * </ul>
     */
    void configure(Map<String, Object> properties);

    /**
     * Open a new single-user realtime session. Implementations SHOULD
     * connect to the provider before returning so the caller can wire
     * listeners and immediately receive {@link RealtimeSession.RealtimeSessionStatus#OPEN}.
     *
     * @throws RealtimeException if authentication, network or provider errors occur.
     */
    RealtimeSession openSession(String tenantId, String personaId) throws RealtimeException;

    /**
     * Release any shared resources (thread pools, connection pools, ...).
     * Called on application shutdown. Does NOT close individual sessions —
     * callers are responsible for closing those.
     */
    void shutdown();
}
