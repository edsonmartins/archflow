package br.com.archflow.api.realtime;

import br.com.archflow.langchain4j.realtime.spi.RealtimeMessage;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Framework-agnostic bridge between the frontend WebSocket and a
 * {@link RealtimeSession}.
 *
 * <p>The bridge is responsible for translating client frames
 * ({@code audio}, {@code text}, {@code stop}) into session method calls
 * and for serializing provider messages back to JSON frames that the
 * frontend's {@code realtime-client.ts} expects.
 *
 * <p>Concrete WebSocket bindings (Spring, Jetty, Netty, ...) instantiate
 * one bridge per connection, wire {@link #outbound(Consumer)} to a send
 * function, and feed every incoming text frame into
 * {@link #onClientFrame(String)}. On disconnect they call {@link #close}.
 *
 * <p>The bridge never blocks — all session output is pushed through the
 * {@code outbound} consumer asynchronously as messages arrive from the
 * provider.
 */
public class RealtimeSessionBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RealtimeSession session;
    private volatile Consumer<String> outboundSink = frame -> {};

    public RealtimeSessionBridge(RealtimeSession session) {
        this.session = session;
        this.session.onMessage(this::emit);
        this.session.onStatus(status -> {
            if (status == RealtimeSession.RealtimeSessionStatus.ERROR) {
                emit(RealtimeMessage.error("provider error"));
            }
        });
    }

    /**
     * Register the outbound sink — typically a lambda that calls
     * {@code session.sendMessage(new TextMessage(frame))} on the
     * framework WebSocket session.
     */
    public void outbound(Consumer<String> sink) {
        this.outboundSink = sink != null ? sink : frame -> {};
    }

    /**
     * Handle an inbound frame from the client. Malformed frames are
     * ignored with a warning.
     */
    public void onClientFrame(String json) {
        if (json == null || json.isBlank()) return;
        Map<String, Object> frame;
        try {
            frame = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[{}] malformed client frame: {}", session.sessionId(), e.getMessage());
            return;
        }
        Object typeObj = frame.get("type");
        if (!(typeObj instanceof String type)) return;
        Object dataObj = frame.get("data");
        Map<String, Object> data = dataObj instanceof Map<?, ?> m
                ? castToStringMap(m)
                : Map.of();

        switch (type) {
            case "audio" -> {
                Object b64 = data.get("pcm16");
                Object rate = data.get("sampleRate");
                if (b64 instanceof String s && rate instanceof Number n) {
                    byte[] pcm = Base64.getDecoder().decode(s);
                    session.sendAudio(pcm, n.intValue());
                }
            }
            case "text" -> {
                Object text = data.get("text");
                if (text instanceof String s) session.sendText(s);
            }
            case "stop" -> close();
            default -> log.trace("[{}] ignored client frame type {}", session.sessionId(), type);
        }
    }

    /**
     * Returns the underlying {@link RealtimeSession} — primarily useful
     * for assertions in tests.
     */
    public RealtimeSession session() {
        return session;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception e) {
            log.warn("error closing session {}: {}", session.sessionId(), e.getMessage());
        }
    }

    private void emit(RealtimeMessage message) {
        try {
            Map<String, Object> envelope = Map.of(
                    "type", message.type(),
                    "data", message.data());
            outboundSink.accept(MAPPER.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("failed to serialize outbound {}: {}", message.type(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
