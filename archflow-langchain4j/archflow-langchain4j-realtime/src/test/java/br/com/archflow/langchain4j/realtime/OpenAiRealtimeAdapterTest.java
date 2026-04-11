package br.com.archflow.langchain4j.realtime;

import br.com.archflow.langchain4j.realtime.openai.OpenAiRealtimeAdapter;
import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapterFactory;
import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import br.com.archflow.langchain4j.realtime.spi.RealtimeMessage;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiRealtimeAdapterTest {

    private OpenAiRealtimeAdapter adapter;
    private FakeRealtimeTransport transport;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiRealtimeAdapter();
        adapter.configure(Map.of(
                "api.key", "sk-test",
                "voice", "alloy",
                "instructions", "Be helpful."));
        transport = new FakeRealtimeTransport();
        adapter.setTransportFactory((uri, key) -> transport);
    }

    @Test
    @DisplayName("configure rejects missing api.key")
    void rejectsMissingApiKey() {
        assertThatThrownBy(() -> new OpenAiRealtimeAdapter().configure(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("openSession fails before configure")
    void openBeforeConfigure() {
        assertThatThrownBy(() -> new OpenAiRealtimeAdapter().openSession("t", "p"))
                .isInstanceOf(RealtimeException.class);
    }

    @Test
    @DisplayName("openSession sends session.update and emits ready")
    void sessionBootstraps() throws Exception {
        List<RealtimeMessage> received = new ArrayList<>();
        RealtimeSession session = adapter.openSession("tenant-1", "order_tracking");
        session.onMessage(received::add);

        // The ready event is fired during open() BEFORE we registered the
        // listener — so we need to also test the path where open() happens
        // with a preregistered listener. Exercise both: emit a server ack
        // and confirm translation.
        transport.receive("{\"type\":\"session.created\"}");

        assertThat(transport.connected).isTrue();
        assertThat(transport.sentText).hasSize(1);
        assertThat(transport.sentText.get(0)).contains("\"session.update\"");
        assertThat(transport.sentText.get(0)).contains("\"voice\":\"alloy\"");
        assertThat(transport.sentText.get(0)).contains("\"instructions\":\"Be helpful.\"");
        assertThat(session.sessionId()).startsWith("rt_");
        assertThat(session.tenantId()).isEqualTo("tenant-1");
        assertThat(session.personaId()).isEqualTo("order_tracking");

        // The session.created echo from the server should produce a ready.
        assertThat(received).anyMatch(m -> m.type().equals("ready"));
    }

    @Test
    @DisplayName("sendAudio emits base64 PCM16 append")
    void sendAudio() throws Exception {
        RealtimeSession session = adapter.openSession("t", "p");
        transport.sentText.clear();

        byte[] pcm = new byte[]{0x01, 0x02, 0x03, 0x04};
        session.sendAudio(pcm, 24_000);

        assertThat(transport.sentText).hasSize(1);
        String frame = transport.sentText.get(0);
        assertThat(frame).contains("\"input_audio_buffer.append\"");
        // base64("\x01\x02\x03\x04") = "AQIDBA=="
        assertThat(frame).contains("AQIDBA==");
    }

    @Test
    @DisplayName("sendText creates conversation item + response.create")
    void sendText() throws Exception {
        RealtimeSession session = adapter.openSession("t", "p");
        transport.sentText.clear();

        session.sendText("hello world");

        assertThat(transport.sentText).hasSize(2);
        assertThat(transport.sentText.get(0)).contains("conversation.item.create");
        assertThat(transport.sentText.get(0)).contains("hello world");
        assertThat(transport.sentText.get(1)).contains("response.create");
    }

    @Test
    @DisplayName("translates OpenAI deltas to RealtimeMessage envelopes")
    void inboundDeltas() throws Exception {
        List<RealtimeMessage> received = new ArrayList<>();
        RealtimeSession session = adapter.openSession("t", "p");
        session.onMessage(received::add);

        transport.receive("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"delta\":\"ras\"}");
        transport.receive("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"rastrear pedido\"}");
        transport.receive("{\"type\":\"response.audio_transcript.delta\",\"delta\":\"Seu pedido \"}");
        transport.receive("{\"type\":\"response.audio_transcript.done\",\"transcript\":\"Seu pedido está em trânsito.\"}");
        transport.receive("{\"type\":\"response.audio.delta\",\"delta\":\"QUJDRA==\"}");
        transport.receive("{\"type\":\"response.done\"}");

        assertThat(received).extracting(RealtimeMessage::type)
                .contains("transcript", "audio", "agent_done");

        List<RealtimeMessage> transcripts = received.stream()
                .filter(m -> m.type().equals("transcript"))
                .toList();
        assertThat(transcripts).hasSize(4);
        assertThat(transcripts.get(0).data().get("speaker")).isEqualTo("user");
        assertThat(transcripts.get(0).data().get("final")).isEqualTo(false);
        assertThat(transcripts.get(1).data().get("final")).isEqualTo(true);
        assertThat(transcripts.get(2).data().get("speaker")).isEqualTo("agent");
        assertThat(transcripts.get(3).data().get("text")).isEqualTo("Seu pedido está em trânsito.");

        RealtimeMessage audio = received.stream()
                .filter(m -> m.type().equals("audio"))
                .findFirst()
                .orElseThrow();
        assertThat(audio.data().get("pcm16")).isEqualTo("QUJDRA==");
        assertThat(audio.data().get("sampleRate")).isEqualTo(24_000);
    }

    @Test
    @DisplayName("translates server error events to error envelopes")
    void inboundError() throws Exception {
        List<RealtimeMessage> received = new ArrayList<>();
        RealtimeSession session = adapter.openSession("t", "p");
        session.onMessage(received::add);

        transport.receive("{\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}");

        assertThat(received).anyMatch(
                m -> m.type().equals("error") && "rate limited".equals(m.data().get("message")));
    }

    @Test
    @DisplayName("close() is idempotent and emits CLOSED status")
    void idempotentClose() throws Exception {
        List<RealtimeSession.RealtimeSessionStatus> statuses = new ArrayList<>();
        RealtimeSession session = adapter.openSession("t", "p");
        session.onStatus(statuses::add);

        session.close();
        session.close();

        assertThat(transport.closed).isTrue();
        assertThat(statuses).contains(RealtimeSession.RealtimeSessionStatus.CLOSED);
    }

    @Test
    @DisplayName("sendAudio after close throws")
    void sendAfterClose() throws Exception {
        RealtimeSession session = adapter.openSession("t", "p");
        session.close();

        assertThatThrownBy(() -> session.sendAudio(new byte[]{0}, 24_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("factory discovers adapter via ServiceLoader")
    void serviceLoaderDiscovery() {
        RealtimeAdapterFactory factory = RealtimeAdapterFactory.findByProvider("openai")
                .orElseThrow();

        assertThat(factory.providerId()).isEqualTo("openai");
        assertThat(factory.create(Map.of("api.key", "sk-x")))
                .isInstanceOf(OpenAiRealtimeAdapter.class);
    }

    @Test
    @DisplayName("findByProvider returns empty for unknown provider")
    void unknownProvider() {
        assertThat(RealtimeAdapterFactory.findByProvider("xyz")).isEmpty();
    }
}
