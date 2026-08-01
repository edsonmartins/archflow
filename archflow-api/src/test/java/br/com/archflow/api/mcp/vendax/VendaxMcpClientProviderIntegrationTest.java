package br.com.archflow.api.mcp.vendax;

import br.com.archflow.langchain4j.mcp.McpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VendaxMcpClientProviderIntegrationTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean failNextList = new AtomicBoolean();
    private final AtomicInteger initializes = new AtomicInteger();
    private final AtomicInteger lists = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            String id = request.path("id").asText();
            if ("initialize".equals(method)) initializes.incrementAndGet();
            if ("tools/list".equals(method)) lists.incrementAndGet();
            if ("tools/list".equals(method) && failNextList.compareAndSet(true, false)) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
                return;
            }
            String result = "initialize".equals(method)
                    ? "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}"
                    : "{\"tools\":[{\"name\":\"echo\",\"description\":\"echo\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                    + "\",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void channelFailureInvalidatesAndReconnectsOnNextLookup() throws Exception {
        VendaxMcpClientProvider provider = new VendaxMcpClientProvider(baseUrl, "token");
        McpClient first = provider.clientFor("tenant");
        failNextList.set(true);

        assertThatThrownBy(() -> first.listTools().get()).hasRootCauseMessage(
                "MCP RPC error 503: HTTP 503 do MCP server: ");

        McpClient reconnected = provider.clientFor("tenant");
        assertThat(reconnected).isNotSameAs(first);
        assertThat(reconnected.listTools().get()).hasSize(1);
        assertThat(initializes).hasValue(2);
    }

    @Test
    void toolCatalogIsCachedPerTenantAndDefinitionVersion() throws Exception {
        VendaxMcpClientProvider provider = new VendaxMcpClientProvider(baseUrl, "token");

        provider.clientFor("tenant", "cs@1").listTools().get();
        provider.clientFor("tenant", "cs@1").listTools().get();
        provider.clientFor("tenant", "cs@2").listTools().get();

        assertThat(lists).hasValue(2);
        assertThat(initializes).hasValue(1);
    }
}
