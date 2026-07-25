package br.com.archflow.langchain4j.mcp.client;

import br.com.archflow.langchain4j.mcp.McpModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interoperabilidade contra um MCP server que fala o transporte
 * <b>Streamable HTTP completo</b>: resposta em SSE, sessão via
 * {@code Mcp-Session-Id} e expiração de sessão com 404.
 *
 * <p>O client era um subset só-JSON e sem sessão — funcionava contra o VendaX
 * Core (stateless) e quebraria contra qualquer server escrito para a spec.
 * Este teste é o que fecha essa incógnita sem depender de um server de
 * terceiro: o servidor abaixo implementa as partes da spec que o client precisa
 * atravessar.
 */
@DisplayName("HttpMcpClient — interop com Streamable HTTP completo")
class HttpMcpClientSpecInteropTest {

    private static final String SESSION = "sess-abc-123";

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    /** Requisições recebidas, para asserção sobre headers. */
    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    /** Quando ligado, o server responde 404 na próxima chamada com sessão. */
    private final AtomicBoolean expireSession = new AtomicBoolean(false);
    private final AtomicInteger initializeCount = new AtomicInteger();
    private final AtomicBoolean deleted = new AtomicBoolean(false);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if ("DELETE".equals(exchange.getRequestMethod())) {
            deleted.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode req = mapper.readTree(raw);
        String method = req.path("method").asText();
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");

        received.add(Map.of(
                "method", method,
                "session", session == null ? "" : session,
                "accept", String.valueOf(exchange.getRequestHeaders().getFirst("Accept")),
                "protocol", String.valueOf(
                        exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))));

        // Sessão vencida: a spec manda 404 para que o client reinicialize.
        if (expireSession.compareAndSet(true, false) && session != null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        String id = req.path("id").asText("1");
        String result = switch (method) {
            case "initialize" -> {
                initializeCount.incrementAndGet();
                yield """
                        {"protocolVersion":"2025-06-18","capabilities":{"tools":{}},\
                        "serverInfo":{"name":"spec-server","version":"1"}}""";
            }
            case "tools/list" -> """
                    {"tools":[{"name":"ler_logs","description":"le logs",\
                    "inputSchema":{"type":"object","properties":{}}}]}""";
            case "tools/call" -> """
                    {"content":[{"type":"text","text":"linha de log"}],"isError":false}""";
            default -> "{}";
        };

        // Resposta em SSE — o outro modo válido da spec, que o client não lia.
        // Inclui um frame de progresso antes, que precisa ser ignorado.
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                + "\"params\":{\"progress\":0.5}}\n"
                + "\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\n"
                + "data: \"result\":" + result + "}\n"
                + "\n";

        byte[] out = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        if ("initialize".equals(method)) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", SESSION);
        }
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private HttpMcpClient connectedClient() throws Exception {
        HttpMcpClient client = new HttpMcpClient(baseUrl, "svc-token", "acme");
        client.connect();
        client.initialize().get();
        return client;
    }

    @Test
    @DisplayName("desembrulha resposta SSE (data: multi-linha) e ignora frame de progresso")
    void parsesSseResponses() throws Exception {
        HttpMcpClient client = connectedClient();

        List<McpModel.Tool> tools = client.listTools().get();

        assertThat(tools).extracting(McpModel.Tool::name).containsExactly("ler_logs");
    }

    @Test
    @DisplayName("captura o Mcp-Session-Id do handshake e o repropaga")
    void propagatesSessionId() throws Exception {
        HttpMcpClient client = connectedClient();
        client.listTools().get();

        assertThat(client.sessionId()).isEqualTo(SESSION);
        assertThat(received).filteredOn(r -> r.get("method").equals("tools/list"))
                .allSatisfy(r -> assertThat(r.get("session")).isEqualTo(SESSION));
    }

    @Test
    @DisplayName("anuncia Accept com os dois modos e a versão do protocolo")
    void announcesTransportCapabilities() throws Exception {
        connectedClient();

        assertThat(received).first().satisfies(r -> {
            assertThat(r.get("accept")).contains("application/json").contains("text/event-stream");
            assertThat(r.get("protocol")).isEqualTo(McpModel.ServerInfo.PROTOCOL_VERSION);
        });
    }

    @Test
    @DisplayName("sessão vencida (404): reinicializa e repete — não fica quebrado para sempre")
    void recoversFromExpiredSession() throws Exception {
        HttpMcpClient client = connectedClient();
        assertThat(initializeCount).hasValue(1);

        expireSession.set(true);
        List<McpModel.Tool> tools = client.listTools().get();

        assertThat(tools).hasSize(1);
        assertThat(initializeCount)
                .as("o client precisa refazer o handshake sozinho")
                .hasValue(2);
    }

    @Test
    @DisplayName("close() encerra a sessão no server")
    void closeTerminatesTheSession() throws Exception {
        HttpMcpClient client = connectedClient();

        client.close();

        assertThat(deleted).isTrue();
        assertThat(client.sessionId()).isNull();
    }

    @Test
    @DisplayName("tools/call atravessa o mesmo caminho SSE + sessão")
    void callToolOverSse() throws Exception {
        HttpMcpClient client = connectedClient();

        McpModel.ToolResult result = client.callTool(
                new McpModel.ToolArguments("ler_logs", Map.of("servico", "traefik"))).get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).singleElement()
                .satisfies(c -> assertThat(c.text()).isEqualTo("linha de log"));
    }
}
