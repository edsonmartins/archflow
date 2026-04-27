package br.com.archflow.langchain4j.realtime.openai;

import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapter;
import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;
import br.com.archflow.langchain4j.realtime.spi.RealtimeTransport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link RealtimeAdapter} implementation that proxies voice sessions
 * to the OpenAI Realtime API.
 *
 * <p>Configuration keys (accepted by {@link #configure(Map)}):
 * <ul>
 *   <li>{@code api.key} <b>(required)</b> — OpenAI API key</li>
 *   <li>{@code base.url} — defaults to {@code wss://api.openai.com/v1/realtime}</li>
 *   <li>{@code model} — defaults to {@code gpt-4o-realtime-preview}</li>
 *   <li>{@code voice} — defaults to {@code alloy}</li>
 *   <li>{@code instructions} — system instructions applied to every session</li>
 * </ul>
 *
 * <p>A custom {@link RealtimeTransport} factory can be injected for tests
 * via {@link #setTransportFactory} so the adapter can be exercised without
 * touching the network.
 */
public class OpenAiRealtimeAdapter implements RealtimeAdapter {

    private static final Logger logger = Logger.getLogger(OpenAiRealtimeAdapter.class.getName());

    public static final String PROVIDER_ID = "openai";
    private static final String DEFAULT_BASE_URL = "wss://api.openai.com/v1/realtime";
    private static final String DEFAULT_MODEL = "gpt-4o-realtime-preview";
    private static final String DEFAULT_VOICE = "alloy";

    private String apiKey;
    private String baseUrl = DEFAULT_BASE_URL;
    private String model = DEFAULT_MODEL;
    private String voice = DEFAULT_VOICE;
    private String instructions = "You are a helpful voice assistant.";

    /**
     * Sessions opened by this adapter. Tracked so {@link #shutdown()}
     * can deterministically close every open WebSocket rather than
     * leaving them to the GC (which would leak file descriptors and
     * keep the provider charging tokens on zombie sessions).
     */
    private final Set<OpenAiRealtimeSession> openSessions =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Test hook: function (endpoint, apiKey) -> transport. Defaults to
     * creating a {@link JdkWebSocketTransport}.
     */
    private BiFunction<URI, String, RealtimeTransport> transportFactory = JdkWebSocketTransport::new;

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public void configure(Map<String, Object> properties) {
        Objects.requireNonNull(properties, "properties");
        Object key = properties.get("api.key");
        if (!(key instanceof String k) || k.isBlank()) {
            throw new IllegalArgumentException("api.key is required");
        }
        this.apiKey = k;
        if (properties.get("base.url") instanceof String b && !b.isBlank()) {
            this.baseUrl = b;
        }
        if (properties.get("model") instanceof String m && !m.isBlank()) {
            this.model = m;
        }
        if (properties.get("voice") instanceof String v && !v.isBlank()) {
            this.voice = v;
        }
        if (properties.get("instructions") instanceof String i && !i.isBlank()) {
            this.instructions = i;
        }
    }

    @Override
    public RealtimeSession openSession(String tenantId, String personaId) throws RealtimeException {
        if (apiKey == null) {
            throw new RealtimeException("adapter not configured — call configure() first");
        }
        URI endpoint = buildEndpoint();
        RealtimeTransport transport = transportFactory.apply(endpoint, apiKey);
        OpenAiRealtimeSession session = new OpenAiRealtimeSession(
                tenantId, personaId, voice, instructions, transport);
        // Register deregister hook BEFORE open() so that even if the
        // transport fails immediately and fires onClose, the tracking
        // set is kept tidy.
        session.setCloseHook(() -> openSessions.remove(session));
        session.open();
        openSessions.add(session);
        return session;
    }

    @Override
    public void shutdown() {
        // Snapshot and clear the tracking set before iterating so a
        // concurrent openSession() cannot add to it while we close.
        Set<OpenAiRealtimeSession> snapshot = Set.copyOf(openSessions);
        openSessions.clear();
        for (OpenAiRealtimeSession session : snapshot) {
            try {
                session.close();
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to close OpenAI realtime session "
                                + session.sessionId() + " during adapter shutdown",
                        e);
            }
        }
    }

    /**
     * Package-visible test hook. Allows swapping the WebSocket factory
     * for an in-memory implementation that does not touch the network.
     */
    public void setTransportFactory(BiFunction<URI, String, RealtimeTransport> factory) {
        this.transportFactory = Objects.requireNonNull(factory);
    }

    private URI buildEndpoint() throws RealtimeException {
        try {
            String separator = baseUrl.contains("?") ? "&" : "?";
            return new URI(baseUrl + separator + "model=" + model);
        } catch (URISyntaxException e) {
            throw new RealtimeException("invalid base.url: " + baseUrl, e);
        }
    }

    // ── accessors for tests ────────────────────────────────────────
    String model() { return model; }
    String voice() { return voice; }
    String instructions() { return instructions; }
}
