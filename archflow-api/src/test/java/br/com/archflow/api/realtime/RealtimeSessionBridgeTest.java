package br.com.archflow.api.realtime;

import br.com.archflow.langchain4j.realtime.spi.RealtimeMessage;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeSessionBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FakeSession session;
    private RealtimeSessionBridge bridge;
    private List<String> outbound;

    @BeforeEach
    void setUp() {
        session = new FakeSession();
        bridge = new RealtimeSessionBridge(session);
        outbound = new ArrayList<>();
        bridge.outbound(outbound::add);
    }

    @Test
    @DisplayName("client audio frame decodes base64 and calls sendAudio")
    void clientAudioFrame() {
        bridge.onClientFrame(
                "{\"type\":\"audio\",\"data\":{\"pcm16\":\"AQID\",\"sampleRate\":24000}}");

        assertThat(session.audioCalls).hasSize(1);
        FakeSession.AudioCall call = session.audioCalls.get(0);
        assertThat(call.sampleRate).isEqualTo(24_000);
        assertThat(call.data).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }

    @Test
    @DisplayName("client text frame forwards to sendText")
    void clientTextFrame() {
        bridge.onClientFrame("{\"type\":\"text\",\"data\":{\"text\":\"oi\"}}");
        assertThat(session.textCalls).containsExactly("oi");
    }

    @Test
    @DisplayName("client stop frame closes the session")
    void clientStopFrame() {
        bridge.onClientFrame("{\"type\":\"stop\"}");
        assertThat(session.closed).isTrue();
    }

    @Test
    @DisplayName("malformed client frame is ignored without throwing")
    void malformedClientFrame() {
        bridge.onClientFrame("not json");
        bridge.onClientFrame("{\"type\":42}");
        assertThat(session.audioCalls).isEmpty();
        assertThat(session.textCalls).isEmpty();
    }

    @Test
    @DisplayName("provider transcripts are serialized to the outbound sink")
    void outboundTranscript() throws Exception {
        session.emit(RealtimeMessage.transcript("user", "rastrear pedido", true));

        assertThat(outbound).hasSize(1);
        Map<String, Object> frame = MAPPER.readValue(outbound.get(0), new TypeReference<>() {});
        assertThat(frame.get("type")).isEqualTo("transcript");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) frame.get("data");
        assertThat(data.get("speaker")).isEqualTo("user");
        assertThat(data.get("text")).isEqualTo("rastrear pedido");
        assertThat(data.get("final")).isEqualTo(true);
    }

    @Test
    @DisplayName("ERROR status is translated to an error frame")
    void errorStatus() {
        session.emitStatus(RealtimeSession.RealtimeSessionStatus.ERROR);
        assertThat(outbound).anyMatch(f -> f.contains("\"error\""));
    }

    @Test
    @DisplayName("close delegates to the underlying session")
    void closeDelegates() {
        bridge.close();
        assertThat(session.closed).isTrue();
    }

    // ── Fake session ───────────────────────────────────────────────

    private static final class FakeSession implements RealtimeSession {

        record AudioCall(byte[] data, int sampleRate) {}

        final List<AudioCall> audioCalls = new ArrayList<>();
        final List<String> textCalls = new ArrayList<>();
        boolean closed = false;
        Consumer<RealtimeMessage> messageListener = m -> {};
        Consumer<RealtimeSessionStatus> statusListener = s -> {};

        @Override public String sessionId() { return "fake_session"; }
        @Override public String tenantId() { return "tenant"; }
        @Override public String personaId() { return "persona"; }

        @Override
        public void sendAudio(byte[] pcm16, int sampleRate) {
            audioCalls.add(new AudioCall(pcm16, sampleRate));
        }

        @Override
        public void sendText(String text) {
            textCalls.add(text);
        }

        @Override
        public void onMessage(Consumer<RealtimeMessage> listener) {
            this.messageListener = listener;
        }

        @Override
        public void onStatus(Consumer<RealtimeSessionStatus> listener) {
            this.statusListener = listener;
        }

        @Override
        public void close() {
            closed = true;
        }

        void emit(RealtimeMessage message) {
            messageListener.accept(message);
        }

        void emitStatus(RealtimeSessionStatus status) {
            statusListener.accept(status);
        }
    }
}
