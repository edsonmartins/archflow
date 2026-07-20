package br.com.archflow.langchain4j.mcp.client;

import br.com.archflow.langchain4j.mcp.McpModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpMcpClient")
class HttpMcpClientTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, String>> receivedHeaders = new CopyOnWriteArrayList<>();
    private final List<String> receivedMethods = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            receivedHeaders.add(Map.of(
                    "Authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "X-TENANT-ID", String.valueOf(exchange.getRequestHeaders().getFirst("X-TENANT-ID"))));
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            JsonNode req = mapper.readTree(reqBytes);
            String method = req.path("method").asText();
            receivedMethods.add(method);
            Object id = req.path("id").isMissingNode() ? null : req.path("id").asText();

            String response = switch (method) {
                case "initialize" -> jsonRpc(id,
                        "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}," +
                        "\"serverInfo\":{\"name\":\"vendax-core\",\"version\":\"1.2.3\"}}");
                case "tools/list" -> jsonRpc(id,
                        "{\"tools\":[{\"name\":\"resolver_sku\",\"description\":\"Resolve SKU\"," +
                        "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"entrada\":{\"type\":\"string\"}}}}]}");
                case "tools/call" -> "boom".equals(req.path("params").path("name").asText())
                        ? "{\"jsonrpc\":\"2.0\",\"id\":\"" + id +
                          "\",\"error\":{\"code\":-32000,\"message\":\"tool exploded\"}}"
                        : jsonRpc(id,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"sku\\\":\\\"ABC\\\",\\\"gate\\\":\\\"RESOLVE\\\"}\"}]," +
                        "\"isError\":false}");
                default -> jsonRpc(id, "{}");
            };
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private String jsonRpc(Object id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + resultJson + "}";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("initialize → tools/list → tools/call, com Authorization Bearer e X-TENANT-ID em toda chamada")
    void handshakeListAndCall() throws Exception {
        HttpMcpClient client = new HttpMcpClient(baseUrl, "svc-token-123", "acme");
        client.connect();

        McpModel.ServerInfo info = client.initialize().get();
        assertThat(info.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(info.serverInfo().name()).isEqualTo("vendax-core");

        List<McpModel.Tool> tools = client.listTools().get();
        assertThat(tools).extracting(McpModel.Tool::name).containsExactly("resolver_sku");
        assertThat(tools.get(0).inputSchema()).containsKey("properties");

        McpModel.ToolResult result = client.callToolSync("resolver_sku", Map.of("entrada", "coca 2l"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content().get(0).text()).contains("\"sku\":\"ABC\"").contains("RESOLVE");

        // Toda chamada carregou os headers de serviço
        assertThat(receivedMethods).containsExactly("initialize", "tools/list", "tools/call");
        assertThat(receivedHeaders).allSatisfy(h -> {
            assertThat(h.get("Authorization")).isEqualTo("Bearer svc-token-123");
            assertThat(h.get("X-TENANT-ID")).isEqualTo("acme");
        });
    }

    @Test
    @DisplayName("erro JSON-RPC vira McpRpcException com código e mensagem")
    void rpcErrorSurfaces() throws Exception {
        HttpMcpClient client = new HttpMcpClient(baseUrl, "t", "acme");
        client.connect();
        assertThatThrownBy(() -> client.callToolSync("boom", Map.of()))
                .cause()
                .isInstanceOf(HttpMcpClient.McpRpcException.class)
                .hasMessageContaining("-32000")
                .hasMessageContaining("tool exploded");
    }
}
