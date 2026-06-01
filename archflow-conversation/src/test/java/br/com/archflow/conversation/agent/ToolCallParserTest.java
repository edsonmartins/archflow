package br.com.archflow.conversation.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCallParser")
class ToolCallParserTest {

    @Test
    @DisplayName("no marker → empty")
    void noMarker() {
        assertThat(ToolCallParser.parse("just a normal answer")).isEmpty();
        assertThat(ToolCallParser.parse(null)).isEmpty();
        assertThat(ToolCallParser.parse("")).isEmpty();
    }

    @Test
    @DisplayName("parses tool name and key=value params")
    void parsesToolAndParams() {
        var call = ToolCallParser.parse("Let me check.\n[TOOL: track_order]\n[PARAMS: orderId=123, channel=web]");

        assertThat(call).isPresent();
        assertThat(call.get().tool()).isEqualTo("track_order");
        assertThat(call.get().params())
                .containsEntry("orderId", "123")
                .containsEntry("channel", "web");
    }

    @Test
    @DisplayName("tool without params yields empty param map")
    void toolWithoutParams() {
        var call = ToolCallParser.parse("[TOOL: list_invoices]");
        assertThat(call).isPresent();
        assertThat(call.get().tool()).isEqualTo("list_invoices");
        assertThat(call.get().params()).isEmpty();
    }

    @Test
    @DisplayName("marker is case-insensitive")
    void caseInsensitive() {
        assertThat(ToolCallParser.parse("[tool: foo]")).isPresent();
    }
}
