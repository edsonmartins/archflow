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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP client sobre o transporte <b>Streamable HTTP</b>.
 *
 * <p>Cada operação é um {@code POST {baseUrl}} JSON-RPC 2.0
 * ({@code initialize} → {@code tools/list} → {@code tools/call}) — por isso NÃO
 * usa a abstração {@link br.com.archflow.langchain4j.mcp.transport.McpTransport}
 * (feita para streams stdio assíncronos com message-handler). Toda chamada
 * carrega os headers de autenticação de serviço:
 * <ul>
 *   <li>{@code Authorization: Bearer <serviceToken>}</li>
 *   <li>{@code X-TENANT-ID: <tenantId>} (o tenant da conversa)</li>
 * </ul>
 *
 * <h2>Compatibilidade com a spec</h2>
 * Este client era um subset só-JSON e sem sessão, o que o quebrava contra
 * qualquer server escrito para a spec completa. Agora ele:
 * <ul>
 *   <li>anuncia {@code Accept: application/json, text/event-stream} e
 *       <b>desembrulha respostas SSE</b> ({@code data:} multi-linha, ignorando
 *       frames de progresso e ficando com a mensagem JSON-RPC);</li>
 *   <li>captura o {@code Mcp-Session-Id} do handshake e o repropaga em toda
 *       requisição;</li>
 *   <li>trata {@code 404} de sessão vencida <b>reinicializando e repetindo uma
 *       vez</b> — sem isso um server que recicla sessões deixava o client
 *       permanentemente quebrado;</li>
 *   <li>encerra a sessão com {@code DELETE} no {@link #close()}.</li>
 * </ul>
 *
 * <p>Não abre o stream GET de servidor→cliente: este client é
 * requisição/resposta e não recebe notificações não solicitadas.
 *
 * <p>O resultado de {@code tools/call} vem em {@code result.content[0].text}
 * (string JSON, repassada como texto); erros em {@code error.{code,message}}.
 * {@code protocolVersion} = {@value McpModel.ServerInfo#PROTOCOL_VERSION}.
 */
public final class HttpMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpClient.class);
    private static final String TENANT_HEADER = "X-TENANT-ID";
    /** Header de sessão do transporte Streamable HTTP. */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final URI endpoint;
    private final String serviceToken;
    private final String tenantId;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ExecutorService ioExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile McpModel.ServerCapabilities capabilities = McpModel.ServerCapabilities.toolsOnly();
    private volatile McpModel.ServerMetadata metadata = new McpModel.ServerMetadata("unknown");
    /** Sessão atribuída pelo server no handshake, quando ele usa sessões. */
    private volatile String sessionId;

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
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout deve ser positivo");
        }
        this.requestTimeout = timeout;
        this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
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
        }, ioExecutor);
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

    /** Sessão corrente, quando o server usa sessões. Visível ao pacote para teste. */
    String sessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        // Encerra a sessão no server (DELETE do Streamable HTTP). Best-effort:
        // um server sem sessões responde 405 e não há nada a fazer.
        String session = sessionId;
        if (session != null && !session.isBlank()) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                        .timeout(requestTimeout)
                        .header(SESSION_HEADER, session)
                        .DELETE();
                if (serviceToken != null && !serviceToken.isBlank()) {
                    req.header("Authorization", "Bearer " + serviceToken);
                }
                http.send(req.build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("DELETE de sessão MCP ignorado pelo server: {}", e.getMessage());
            }
        }
        sessionId = null;
        connected.set(false);
        ioExecutor.shutdownNow();
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
        }, ioExecutor);
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", arguments.name());
        params.put("arguments", arguments.arguments());
        // CAPTURA AQUI, na thread do chamador. O corpo abaixo roda numa thread virtual do
        // ioExecutor, onde o ThreadLocal do dispatcher não existe — ler lá dentro compilaria e
        // devolveria nulo SEMPRE, sem erro nenhum, e a correlação simplesmente não apareceria.
        CorrelacaoMcp.Dados correlacao = CorrelacaoMcp.atual();
        return CompletableFuture.supplyAsync(() -> {
            JsonNode result = rpc("tools/call", params, correlacao);
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
        }, ioExecutor);
    }

    // ── JSON-RPC over HTTP ──────────────────────────────────────────

    /** Executa um método JSON-RPC e devolve o nó {@code result}, ou lança em {@code error}. */
    private JsonNode rpc(String method, Map<String, Object> params) {
        return rpc(method, params, new CorrelacaoMcp.Dados(null, null));
    }

    /** Idem, carregando a correlação da execução — só {@code tools/call} a tem. */
    private JsonNode rpc(String method, Map<String, Object> params, CorrelacaoMcp.Dados correlacao) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", method);
        body.put("params", params);

        JsonNode response = postRaw(body, true, correlacao);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpRpcException(
                    error.path("code").asInt(-1),
                    error.path("message").asText("erro MCP sem mensagem"));
        }
        return response.path("result");
    }

    private JsonNode postRaw(Map<String, Object> body) {
        return postRaw(body, true, new CorrelacaoMcp.Dados(null, null));
    }

    /**
     * @param mayRetry se pode reinicializar a sessão e repetir uma vez. Um server
     *                 que recicla sessões responde 404 a um {@code Mcp-Session-Id}
     *                 vencido; sem isto, o client ficaria permanentemente quebrado
     *                 até alguém reconfigurá-lo à mão.
     */
    private JsonNode postRaw(Map<String, Object> body, boolean mayRetry) {
        return postRaw(body, mayRetry, new CorrelacaoMcp.Dados(null, null));
    }

    private JsonNode postRaw(Map<String, Object> body, boolean mayRetry,
                             CorrelacaoMcp.Dados correlacao) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    // A spec do Streamable HTTP permite ao server responder com um
                    // JSON único OU com um stream SSE; anunciamos os dois.
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", McpModel.ServerInfo.PROTOCOL_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (serviceToken != null && !serviceToken.isBlank()) {
                req.header("Authorization", "Bearer " + serviceToken);
            }
            if (tenantId != null && !tenantId.isBlank()) {
                req.header(TENANT_HEADER, tenantId);
            }
            // A correlação da execução. Ausente em initialize/tools-list e em qualquer chamada
            // fora de um invoke — o server trata a ausência como o caso normal.
            if (correlacao != null && correlacao.janelaChave() != null
                    && !correlacao.janelaChave().isBlank()) {
                req.header(CorrelacaoMcp.HEADER_JANELA, correlacao.janelaChave());
            }
            if (correlacao != null && correlacao.traceId() != null
                    && !correlacao.traceId().isBlank()) {
                req.header(CorrelacaoMcp.HEADER_TRACE, correlacao.traceId());
            }
            String session = sessionId;
            if (session != null && !session.isBlank()) {
                req.header(SESSION_HEADER, session);
            }

            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());

            // Sessão vencida/desconhecida: reinicializa e repete uma vez.
            // A spec manda 404, mas servidores reais também devolvem 400 para um
            // Mcp-Session-Id que não reconhecem — os dois significam a mesma
            // coisa do nosso lado, e tratar só o 404 deixaria o client preso.
            if (isStaleSession(res.statusCode()) && session != null && mayRetry) {
                log.info("Sessão MCP {} expirada no server; reinicializando", session);
                sessionId = null;
                reinitialize();
                return postRaw(body, false, correlacao);
            }
            if (res.statusCode() / 100 != 2) {
                throw new McpRpcException(res.statusCode(),
                        "HTTP " + res.statusCode() + " do MCP server: " + truncate(res.body()));
            }

            res.headers().firstValue(SESSION_HEADER)
                    .filter(id -> !id.isBlank())
                    .ifPresent(id -> this.sessionId = id);

            String bodyText = res.body();
            if (bodyText == null || bodyText.isBlank()) {
                return mapper.createObjectNode();
            }
            String contentType = res.headers().firstValue("Content-Type").orElse("");
            if (contentType.toLowerCase().contains("text/event-stream")) {
                return parseSseEnvelope(bodyText);
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

    /**
     * Extrai a mensagem JSON-RPC de uma resposta SSE.
     *
     * <p>O corpo vem em frames {@code event:}/{@code data:} separados por linha em
     * branco, e {@code data:} pode ocupar várias linhas (concatenadas com
     * {@code \n}, conforme a spec de SSE). Interessa o último frame que carregue
     * uma resposta JSON-RPC — os anteriores podem ser notificações de progresso.
     */
    private JsonNode parseSseEnvelope(String body) throws IOException {
        JsonNode last = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\r?\n", -1)) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && data.length() > 0) {
                JsonNode parsed = tryParse(data.toString());
                if (parsed != null && (parsed.has("result") || parsed.has("error"))) {
                    last = parsed;
                }
                data.setLength(0);
            }
            // linhas event:/id:/retry: e comentários (":") não afetam o payload
        }
        if (data.length() > 0) {
            JsonNode parsed = tryParse(data.toString());
            if (parsed != null && (parsed.has("result") || parsed.has("error"))) {
                last = parsed;
            }
        }
        if (last == null) {
            throw new IOException("resposta SSE sem mensagem JSON-RPC: " + truncate(body));
        }
        return last;
    }

    private JsonNode tryParse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isStaleSession(int statusCode) {
        return statusCode == 404 || statusCode == 400;
    }

    /** Refaz o handshake após perder a sessão. */
    private void reinitialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpModel.ServerInfo.PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "archflow", "version", "1.0.0"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", "initialize");
        body.put("params", params);
        postRaw(body, false);
        connected.set(true);
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
