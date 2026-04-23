package br.com.archflow.api.realtime;

import br.com.archflow.langchain4j.realtime.spi.RealtimeMessage;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevRealtimeAdapterTest {

    @Test
    @DisplayName("open session emits ready and deterministic transcript events")
    void openSession() throws Exception {
        var adapter = new DevRealtimeAdapter();
        RealtimeSession session = adapter.openSession("tenant_rio_quality", "order_tracking");
        List<RealtimeMessage> messages = new ArrayList<>();
        List<RealtimeSession.RealtimeSessionStatus> statuses = new ArrayList<>();

        session.onMessage(messages::add);
        session.onStatus(statuses::add);
        session.sendAudio(new byte[] {1, 2, 3}, 24_000);

        assertThat(statuses).contains(RealtimeSession.RealtimeSessionStatus.OPEN);
        assertThat(messages).extracting(RealtimeMessage::type)
                .containsExactly("ready", "transcript", "transcript", "agent_done");
        assertThat(messages.get(1).data()).containsEntry("speaker", "user");
        assertThat(messages.get(2).data()).containsEntry("speaker", "agent");
        assertThat(messages.get(2).data().get("text")).asString().contains("tenant_rio_quality");
    }
}
