package br.com.archflow.langchain4j.realtime.spi;

import java.util.Map;

/**
 * Uniform envelope for events flowing over a realtime session.
 *
 * <p>Mirrors the JSON shape exchanged with the frontend's
 * {@code realtime-client.ts}: {@code {type, data}}. Providers translate
 * their native events into this envelope so the upstream consumer
 * (typically the {@code RealtimeController} in {@code archflow-api})
 * can forward them to the browser without knowing which provider is
 * behind the session.
 *
 * <p>Supported types (provider-agnostic):
 * <ul>
 *   <li>{@code ready} — session established, {@code data.sessionId} set</li>
 *   <li>{@code transcript} — ASR fragment, {@code data.speaker},
 *       {@code data.text}, {@code data.final}</li>
 *   <li>{@code audio} — TTS audio frame, {@code data.pcm16} (base64),
 *       {@code data.sampleRate}</li>
 *   <li>{@code agent_done} — agent turn completed</li>
 *   <li>{@code error} — {@code data.message}</li>
 * </ul>
 */
public record RealtimeMessage(String type, Map<String, Object> data) {

    public RealtimeMessage {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        if (data == null) data = Map.of();
    }

    public static RealtimeMessage ready(String sessionId) {
        return new RealtimeMessage("ready", Map.of("sessionId", sessionId));
    }

    public static RealtimeMessage transcript(String speaker, String text, boolean isFinal) {
        return new RealtimeMessage(
                "transcript",
                Map.of("speaker", speaker, "text", text, "final", isFinal));
    }

    public static RealtimeMessage audio(String pcm16Base64, int sampleRate) {
        return new RealtimeMessage(
                "audio",
                Map.of("pcm16", pcm16Base64, "sampleRate", sampleRate));
    }

    public static RealtimeMessage agentDone() {
        return new RealtimeMessage("agent_done", Map.of());
    }

    public static RealtimeMessage error(String message) {
        return new RealtimeMessage("error", Map.of("message", message));
    }
}
