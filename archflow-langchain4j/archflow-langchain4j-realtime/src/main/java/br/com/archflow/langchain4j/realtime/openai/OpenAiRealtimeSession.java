package br.com.archflow.langchain4j.realtime.openai;

import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import br.com.archflow.langchain4j.realtime.spi.RealtimeMessage;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;
import br.com.archflow.langchain4j.realtime.spi.RealtimeTransport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * OpenAI Realtime API session.
 *
 * <p>Translates between the neutral {@link RealtimeMessage} envelope used
 * by the rest of ArchFlow and the native event protocol of the OpenAI
 * Realtime API. Inbound events that matter to the UI:
 *
 * <ul>
 *   <li>{@code session.created} → {@link RealtimeMessage#ready}</li>
 *   <li>{@code conversation.item.input_audio_transcription.delta} → user transcript (partial)</li>
 *   <li>{@code conversation.item.input_audio_transcription.completed} → user transcript (final)</li>
 *   <li>{@code response.audio_transcript.delta} → agent transcript (partial)</li>
 *   <li>{@code response.audio_transcript.done} → agent transcript (final)</li>
 *   <li>{@code response.audio.delta} → TTS audio chunk (PCM16 base64)</li>
 *   <li>{@code response.done} → {@link RealtimeMessage#agentDone}</li>
 *   <li>{@code error} → {@link RealtimeMessage#error}</li>
 * </ul>
 *
 * <p>Outbound events sent to OpenAI:
 * <ul>
 *   <li>{@code session.update} on connect (voice, modalities, instructions)</li>
 *   <li>{@code input_audio_buffer.append} for every mic frame</li>
 *   <li>{@code conversation.item.create} + {@code response.create} for text input</li>
 * </ul>
 */
public class OpenAiRealtimeSession implements RealtimeSession {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRealtimeSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final String tenantId;
    private final String personaId;
    private final String voice;
    private final String instructions;
    private final RealtimeTransport transport;

    private volatile Consumer<RealtimeMessage> messageListener = m -> {};
    private volatile Consumer<RealtimeSessionStatus> statusListener = s -> {};
    private volatile boolean closed = false;

    public OpenAiRealtimeSession(
            String tenantId,
            String personaId,
            String voice,
            String instructions,
            RealtimeTransport transport) {
        this.sessionId = "rt_" + UUID.randomUUID();
        this.tenantId = tenantId;
        this.personaId = personaId;
        this.voice = voice;
        this.instructions = instructions;
        this.transport = transport;

        transport.onText(this::handleInbound);
        transport.onError(err -> {
            log.warn("[{}] transport error: {}", sessionId, err.getMessage());
            messageListener.accept(RealtimeMessage.error(err.getMessage()));
            statusListener.accept(RealtimeSessionStatus.ERROR);
        });
        transport.onClose(reason -> {
            if (!closed) {
                closed = true;
                statusListener.accept(RealtimeSessionStatus.CLOSED);
            }
        });
    }

    /**
     * Open the transport and send the initial {@code session.update}
     * event that configures voice, modalities and instructions.
     */
    public void open() throws RealtimeException {
        statusListener.accept(RealtimeSessionStatus.CONNECTING);
        transport.connect();

        Map<String, Object> sessionUpdate = Map.of(
                "type", "session.update",
                "session", Map.of(
                        "modalities", java.util.List.of("text", "audio"),
                        "voice", voice,
                        "instructions", instructions,
                        "input_audio_format", "pcm16",
                        "output_audio_format", "pcm16",
                        "input_audio_transcription", Map.of("model", "whisper-1")));
        send(sessionUpdate);

        statusListener.accept(RealtimeSessionStatus.OPEN);
        messageListener.accept(RealtimeMessage.ready(sessionId));
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public String tenantId() {
        return tenantId;
    }

    @Override
    public String personaId() {
        return personaId;
    }

    @Override
    public void sendAudio(byte[] pcm16, int sampleRate) {
        if (closed) throw new IllegalStateException("session closed");
        String b64 = Base64.getEncoder().encodeToString(pcm16);
        send(Map.of(
                "type", "input_audio_buffer.append",
                "audio", b64));
    }

    @Override
    public void sendText(String text) {
        if (closed) throw new IllegalStateException("session closed");
        send(Map.of(
                "type", "conversation.item.create",
                "item", Map.of(
                        "type", "message",
                        "role", "user",
                        "content", java.util.List.of(
                                Map.of("type", "input_text", "text", text)))));
        send(Map.of("type", "response.create"));
    }

    @Override
    public void onMessage(Consumer<RealtimeMessage> listener) {
        this.messageListener = listener != null ? listener : m -> {};
    }

    @Override
    public void onStatus(Consumer<RealtimeSessionStatus> listener) {
        this.statusListener = listener != null ? listener : s -> {};
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            transport.close();
        } catch (Exception ignored) {
        }
        statusListener.accept(RealtimeSessionStatus.CLOSED);
    }

    // ── Inbound handling ──────────────────────────────────────────

    void handleInbound(String json) {
        if (json == null || json.isBlank()) return;
        Map<String, Object> evt;
        try {
            evt = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[{}] failed to parse inbound JSON: {}", sessionId, e.getMessage());
            return;
        }
        Object typeObj = evt.get("type");
        if (!(typeObj instanceof String type)) return;

        switch (type) {
            case "session.created" -> messageListener.accept(RealtimeMessage.ready(sessionId));
            case "conversation.item.input_audio_transcription.delta" -> {
                String text = str(evt.get("delta"));
                if (text != null) messageListener.accept(RealtimeMessage.transcript("user", text, false));
            }
            case "conversation.item.input_audio_transcription.completed" -> {
                String text = str(evt.get("transcript"));
                if (text != null) messageListener.accept(RealtimeMessage.transcript("user", text, true));
            }
            case "response.audio_transcript.delta" -> {
                String text = str(evt.get("delta"));
                if (text != null) messageListener.accept(RealtimeMessage.transcript("agent", text, false));
            }
            case "response.audio_transcript.done" -> {
                String text = str(evt.get("transcript"));
                if (text != null) messageListener.accept(RealtimeMessage.transcript("agent", text, true));
            }
            case "response.audio.delta" -> {
                String b64 = str(evt.get("delta"));
                if (b64 != null) messageListener.accept(RealtimeMessage.audio(b64, 24_000));
            }
            case "response.done" -> messageListener.accept(RealtimeMessage.agentDone());
            case "error" -> {
                Object errObj = evt.get("error");
                String msg = errObj instanceof Map<?, ?> errMap
                        ? String.valueOf(errMap.get("message"))
                        : String.valueOf(errObj);
                messageListener.accept(RealtimeMessage.error(msg));
            }
            default -> log.trace("[{}] ignored event {}", sessionId, type);
        }
    }

    private void send(Map<String, Object> payload) {
        try {
            transport.send(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[{}] failed to send frame: {}", sessionId, e.getMessage());
            messageListener.accept(RealtimeMessage.error("outbound frame failed: " + e.getMessage()));
        }
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }
}
