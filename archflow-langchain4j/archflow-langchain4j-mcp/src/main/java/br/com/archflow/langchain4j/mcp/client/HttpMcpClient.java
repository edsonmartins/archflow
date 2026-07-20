package br.com.archflow.langchain4j.mcp.client;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP client sobre HTTP (subset "Streamable HTTP" sem SSE) — o transporte que
 * faltava para consumir MCP servers HTTP como o VendaX Core.
 *
 * <p>Cada operação é um {@code POST {baseUrl}} JSON-RPC 2.0
 * ({@code initialize} → {@code tools/list} → {@code tools/call}) numa única
 * requisição/resposta — por isso NÃO usa a abstração {@link br.com.archflow.langchain4j.mcp.transport.McpTransport}
 * (feita para streams stdio assíncronos com message-handler). Toda chamada
 * carrega os headers de autenticação de serviço:
 * <ul>
 *   <li>{@code Authorization: Bearer <serviceToken>}</li>
 *   <li>{@code X-TENANT-ID: <tenantId>} (o tenant da conversa)</li>
 * </ul>
 *
 * <p>O resultado de {@code tools/call} vem em {@code result.content[0].text}
 * (string JSON, repassada como texto); erros em {@code error.{code,message}}.
 * {@code protocolVersion} = {@value McpModel.ServerInfo#PROTOCOL_VERSION}.
 */
public final class HttpMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpClient.class);
    private static final String TENANT_HEADER = "X-TENANT-ID";

    private final URI endpoint;
    private final String serviceToken;
    private final String tenantId;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile McpModel.ServerCapabilities capabilities = McpModel.ServerCapabilities.toolsOnly();
    private volatile McpModel.ServerMetadata metadata = new McpModel.ServerMetadata("unknown");

    public HttpMcpClient(String baseUrl, String serviceToken, String tenantId) {
        this(baseUrl, serviceToken, tenantId, Duration.ofSeconds(30));
    }

    public HttpMcpClient(String baseUrl, String serviceToken, String tenantId, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl é obrigatório (endpoint /mcp do server)");
        }
        this.endpoint = URI.create(baseUrl);
        this.serviceToken = serviceToken;
        this.tenantId = tenantId;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void connect() throws IOException {
        // Sem conexão persistente: o handshake é o initialize(). Marca conectado
        // para que initialize()/listTools() possam prosseguir.
        connected.set(true);
    }

    @Override
    public CompletableFuture<McpModel.ServerInfo> initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpModel.ServerInfo.PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "archflow", "version", "1.0.0"));
        return CompletableFuture.supplyAsync(() -> {
            JsonNode result = rpc("initialize", params);
            McpModel.ServerCapabilities caps = parseCapabilities(result.path("capabilities"));
            McpModel.ServerMetadata meta = parseMetadata(result.path("serverInfo"));
            this.capabilities = caps;
            this.metadata = meta;
            connected.set(true);
            String protocol = result.path("protocolVersion").asText(McpModel.ServerInfo.PROTOCOL_VERSION);
            return new McpModel.ServerInfo(protocol, caps, meta);
        });
    }

    @Override
    public void initialized() {
        // Notificação notifications/initialized — best-effort; servers HTTP
        // stateless (como o VendaX Core) toleram a ausência. Não bloqueia.
        try {
            postRaw(Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized",
                    "params", Map.of()));
        } catch (Exception e) {
            log.debug("notifications/initialized ignorado pelo server: {}", e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
    }

    @Override
    public McpModel.ServerCapabilities getServerCapabilities() {
        return capabilities;
    }

    @Override
    public McpModel.ServerMetadata getServerMetadata() {
        return metadata;
    }

    @Override
    public CompletableFuture<List<McpModel.Tool>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            JsonNode result = rpc("tools/list", Map.of());
            List<McpModel.Tool> tools = new ArrayList<>();
            for (JsonNode t : result.path("tools")) {
                String name = t.path("name").asText(null);
                String description = t.path("description").asText("");
                Map<String, Object> schema = jsonToMap(t.path("inputSchema"));
                if (schema.isEmpty()) {
                    // O record Tool rejeita inputSchema vazio; um objeto vazio é
                    // um schema válido de "sem parâmetros".
                    schema = Map.of("type", "object", "properties", Map.of());
                }
                if (description.isBlank()) {
                    description = name; // record exige descrição não-vazia
                }
                tools.add(new McpModel.Tool(name, description, schema));
            }
            return tools;
        });
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", arguments.name());
        params.put("arguments", arguments.arguments());
        return CompletableFuture.supplyAsync(() -> {
            JsonNode result = rpc("tools/call", params);
            List<McpModel.ToolContent> content = new ArrayList<>();
            for (JsonNode c : result.path("content")) {
                content.add(new McpModel.ToolContent(
                        c.path("type").asText("text"),
                        c.hasNonNull("text") ? c.path("text").asText() : null,
                        c.hasNonNull("data") ? c.path("data").asText() : null,
                        c.hasNonNull("uri") ? URI.create(c.path("uri").asText()) : null));
            }
            boolean isError = result.path("isError").asBoolean(false);
            return new McpModel.ToolResult(content, isError);
        });
    }

    // ── JSON-RPC over HTTP ──────────────────────────────────────────

    /** Executa um método JSON-RPC e devolve o nó {@code result}, ou lança em {@code error}. */
    private JsonNode rpc(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", method);
        body.put("params", params);

        JsonNode response = postRaw(body);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpRpcException(
                    error.path("code").asInt(-1),
                    error.path("message").asText("erro MCP sem mensagem"));
        }
        return response.path("result");
    }

    private JsonNode postRaw(Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (serviceToken != null && !serviceToken.isBlank()) {
                req.header("Authorization", "Bearer " + serviceToken);
            }
            if (tenantId != null && !tenantId.isBlank()) {
                req.header(TENANT_HEADER, tenantId);
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new McpRpcException(res.statusCode(),
                        "HTTP " + res.statusCode() + " do MCP server: " + truncate(res.body()));
            }
            String bodyText = res.body();
            if (bodyText == null || bodyText.isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(bodyText);
        } catch (McpRpcException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Falha na chamada MCP HTTP para " + endpoint + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, Map.class);
    }

    private McpModel.ServerCapabilities parseCapabilities(JsonNode node) {
        boolean tools = node.has("tools");
        boolean resources = node.has("resources");
        boolean prompts = node.has("prompts");
        boolean logging = node.has("logging");
        return new McpModel.ServerCapabilities(resources, tools || (!resources && !prompts), prompts, logging);
    }

    private McpModel.ServerMetadata parseMetadata(JsonNode node) {
        String name = node.path("name").asText("");
        String version = node.path("version").asText("1.0.0");
        return new McpModel.ServerMetadata(name.isBlank() ? "mcp-server" : name, version);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** Erro JSON-RPC ({@code error.code}/{@code error.message}) do MCP server. */
    public static final class McpRpcException extends RuntimeException {
        private final int code;

        public McpRpcException(int code, String message) {
            super("MCP RPC error " + code + ": " + message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
