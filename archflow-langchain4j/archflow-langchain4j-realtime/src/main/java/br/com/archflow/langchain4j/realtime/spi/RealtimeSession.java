package br.com.archflow.langchain4j.realtime.spi;

import java.util.function.Consumer;

/**
 * Represents an active realtime voice session between one tenant/persona
 * and one external provider (OpenAI Realtime, Gemini Live, etc).
 *
 * <p>Implementations are <b>single-user</b> and are created by
 * {@link RealtimeAdapter#openSession}. The owning
 * {@code RealtimeController} in {@code archflow-api} is responsible for
 * pairing one frontend WebSocket with one {@code RealtimeSession}, forwarding
 * client frames via {@link #sendAudio}/{@link #sendText} and pushing provider
 * events back to the frontend through the listener registered in
 * {@link #onMessage}.
 *
 * <p>Sessions are NOT thread-safe by contract — implementations may or may
 * not synchronize internally. Use a single caller thread or serialize
 * externally.
 */
public interface RealtimeSession extends AutoCloseable {

    /**
     * Stable identifier assigned at session creation time. Used for
     * correlation in audit/stream events.
     */
    String sessionId();

    /**
     * The tenant this session belongs to. Always non-null; propagated to
     * every {@link RealtimeMessage} emitted by this session via envelope
     * metadata (handled upstream).
     */
    String tenantId();

    /**
     * The persona id selected for this session. Maps 1:1 to the persona
     * resolver entry in the SAC/orchestrator stack.
     */
    String personaId();

    /**
     * Forward a PCM16 little-endian audio frame from the user microphone
     * to the provider. {@code sampleRate} is typically 16_000 or 24_000 Hz.
     *
     * @param pcm16       raw PCM16 little-endian bytes (mono)
     * @param sampleRate  sample rate in Hz
     * @throws IllegalStateException if the session is already closed
     */
    void sendAudio(byte[] pcm16, int sampleRate);

    /**
     * Forward a text message (out-of-band text input) to the provider.
     * Optional — some providers require a separate endpoint or ignore this.
     */
    void sendText(String text);

    /**
     * Register a listener that receives every {@link RealtimeMessage}
     * emitted by the provider (transcripts, audio frames, errors, done).
     * Only one listener per session is supported; later calls replace
     * the previous registration.
     */
    void onMessage(Consumer<RealtimeMessage> listener);

    /**
     * Register a status listener (open, closed, error, ...). Matches the
     * same enum semantics as the frontend realtime client status badge.
     */
    void onStatus(Consumer<RealtimeSessionStatus> listener);

    /**
     * Gracefully terminate the session and release all resources.
     * Idempotent — safe to call multiple times.
     */
    @Override
    void close();

    enum RealtimeSessionStatus {
        CONNECTING,
        OPEN,
        ERROR,
        CLOSED
    }
}
