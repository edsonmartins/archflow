package br.com.archflow.langchain4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonRpc message model")
class JsonRpcTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestCreate() {
        var req = JsonRpc.Request.create("tools/list", Map.of("cursor", "abc"));
        assertThat(req.jsonrpc()).isEqualTo("2.0");
        assertThat(req.method()).isEqualTo("tools/list");
        assertThat(req.params()).containsEntry("cursor", "abc");
        assertThat(req.id()).isNotNull();
    }

    @Test
    void requestRejectsNon2_0() {
        assertThatThrownBy(() -> new JsonRpc.Request("1.0", "id", "method", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestSerializesToJson() throws Exception {
        var req = JsonRpc.Request.create("initialize", Map.of());
        String json = mapper.writeValueAsString(req);
        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"method\":\"initialize\"");
    }

    @Test
    void responseSuccess() {
        var resp = JsonRpc.Response.success("req-1", Map.of("tools", java.util.List.of()));
        assertThat(resp.jsonrpc()).isEqualTo("2.0");
        assertThat(resp.id()).isEqualTo("req-1");
        assertThat(resp.result()).isNotNull();
        assertThat(resp.error()).isNull();
    }

    @Test
    void responseError() {
        var err = new JsonRpc.JsonRpcError(-32600, "Invalid Request", null);
        var resp = JsonRpc.Response.error("req-2", err);
        assertThat(resp.result()).isNull();
        assertThat(resp.error().code()).isEqualTo(-32600);
        assertThat(resp.error().message()).isEqualTo("Invalid Request");
    }

    @Test
    void responseRejectsNon2_0() {
        assertThatThrownBy(() -> new JsonRpc.Response("1.0", "id", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notificationCreate() {
        var notif = JsonRpc.Request.createNotification("initialized", Map.of());
        assertThat(notif.jsonrpc()).isEqualTo("2.0");
        assertThat(notif.method()).isEqualTo("initialized");
    }

    @Test
    void notificationRejectsNon2_0() {
        assertThatThrownBy(() -> new JsonRpc.Notification("1.0", "method", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonRpcErrorRecord() {
        var err = new JsonRpc.JsonRpcError(-32601, "Method not found", Map.of("detail", "xyz"));
        assertThat(err.code()).isEqualTo(-32601);
        assertThat(err.message()).isEqualTo("Method not found");
        assertThat(err.data()).isNotNull();
    }

    @Test
    void roundTripRequest() throws Exception {
        var req = JsonRpc.Request.create("tools/call", Map.of("name", "search", "args", Map.of("q", "java")));
        String json = mapper.writeValueAsString(req);
        var parsed = mapper.readValue(json, JsonRpc.Request.class);
        assertThat(parsed.method()).isEqualTo("tools/call");
        assertThat(parsed.id()).isEqualTo(req.id());
    }

    @Test
    void roundTripResponse() throws Exception {
        var resp = JsonRpc.Response.success("id-1", Map.of("content", "hello"));
        String json = mapper.writeValueAsString(resp);
        var parsed = mapper.readValue(json, JsonRpc.Response.class);
        assertThat(parsed.id()).isEqualTo("id-1");
        assertThat(parsed.result()).isNotNull();
    }
}
