package br.com.archflow.langchain4j.mcp.server;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.McpServer;
import br.com.archflow.langchain4j.mcp.transport.McpTransport;
import br.com.archflow.langchain4j.mcp.transport.StdioServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base implementation of MCP Server.
 *
 * <p>This class provides the common functionality for MCP servers,
 * including JSON-RPC message handling and dispatching to the appropriate methods.</p>
 *
 * <h3>Subclassing:</h3>
 * <p>Subclasses should override the methods for the capabilities they support:
 * <ul>
 *   <li>{@link #listResources()}, {@link #readResource(URI)} for resources</li>
 *   <li>{@link #listTools()}, {@link #callTool(McpModel.ToolArguments)} for tools</li>
 *   <li>{@link #listPrompts()}, {@link #getPrompt(String, Map)} for prompts</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * public class MyMcpServer extends AbstractMcpServer {
 *
 *     public MyMcpServer() {
 *         super("MyServer", McpModel.ServerCapabilities.toolsOnly());
 *     }
 *
 *     @Override
 *     public List<Tool> listTools() {
 *         return List.of(
 *             new Tool("echo", "Echo back the input", inputSchema)
 *         );
 *     }
 *
 *     @Override
 *     public CompletableFuture<ToolResult> callTool(ToolArguments arguments) {
 *         return CompletableFuture.completedFuture(
 *             ToolResult.text("Echo: " + arguments.arguments().get("input"))
 *         );
 *     }
 * }
 * }</pre>
 *
 * @see McpServer
 */
public abstract class AbstractMcpServer implements McpServer {

    private static final Logger log = LoggerFactory.getLogger(AbstractMcpServer.class);

    private final McpModel.ServerMetadata serverInfo;
    private final McpModel.ServerCapabilities capabilities;
    private final Map<String, Object> state;
    private McpTransport transport;
    private volatile boolean initialized = false;

    /**
     * Create an MCP server with the given info and capabilities.
     *
     * @param name Server name
     * @param capabilities Server capabilities
     */
    protected AbstractMcpServer(String name, McpModel.ServerCapabilities capabilities) {
        this(name, "1.0.0", capabilities);
    }

    /**
     * Create an MCP server with full info.
     *
     * @param name Server name
     * @param version Server version
     * @param capabilities Server capabilities
     */
    protected AbstractMcpServer(String name, String version, McpModel.ServerCapabilities capabilities) {
        this.serverInfo = new McpModel.ServerMetadata(name, version);
        this.capabilities = capabilities;
        this.state = new ConcurrentHashMap<>();
    }

    @Override
    public McpModel.ServerMetadata getServerInfo() {
        return serverInfo;
    }

    @Override
    public McpModel.ServerCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public CompletableFuture<McpModel.InitializeResult> initialize(McpModel.ClientInfo clientInfo) {
        log.info("Initializing MCP server '{}' with client '{}'",
                serverInfo.name(), clientInfo.clientInfo().name());
        initialized = true;
        return CompletableFuture.completedFuture(
                new McpModel.InitializeResult(capabilities, serverInfo)
        );
    }

    @Override
    public void initialized() {
        log.debug("MCP server '{}' initialized notification received", serverInfo.name());
    }

    @Override
    public void shutdown() {
        log.info("Shutting down MCP server '{}'", serverInfo.name());
        initialized = false;
        if (transport != null) {
            transport.stop();
        }
    }

    /**
     * Set the transport for this server.
     *
     * @param transport Transport to use
     */
    protected void setTransport(McpTransport transport) {
        this.transport = transport;
    }

    /**
     * Get the transport.
     *
     * @return Transport
     */
    protected McpTransport getTransport() {
        return transport;
    }

    /**
     * Check if server is initialized.
     *
     * @return true if initialized
     */
    protected boolean isInitialized() {
        return initialized;
    }

    /**
     * Get server state storage.
     *
     * @return State map
     */
    protected Map<String, Object> getState() {
        return state;
    }

    /**
     * Handle a JSON-RPC request.
     *
     * @param request Request to handle
     * @return Response
     */
    public JsonRpc.Response handleRequest(JsonRpc.Request request) {
        String method = request.method();

        try {
            return switch (method) {
                // Lifecycle
                case "initialize" -> handleInitialize(request);
                case "ping" -> handlePing(request);

                // Resources
                case "resources/list" -> handleListResources(request);
                case "resources/read" -> handleReadResource(request);
                case "resources/templates/list" -> handleListResourceTemplates(request);
                case "resources/subscribe" -> handleSubscribeResource(request);
                case "resources/unsubscribe" -> handleUnsubscribeResource(request);

                // Tools
                case "tools/list" -> handleListTools(request);
                case "tools/call" -> handleCallTool(request);

                // Prompts
                case "prompts/list" -> handleListPrompts(request);
                case "prompts/get" -> handleGetPrompt(request);

                default -> JsonRpc.Response.error(request.id(),
                        JsonRpc.JsonRpcError.methodNotFound(method));
            };
        } catch (Exception e) {
            log.error("Error handling request: {}", method, e);
            return JsonRpc.Response.error(request.id(),
                    JsonRpc.JsonRpcError.internalError(e.getMessage()));
        }
    }

    private JsonRpc.Response handleInitialize(JsonRpc.Request request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = request.params();

        String protocolVersion = (String) params.get("protocolVersion");
        log.debug("Initialize request, protocol version: {}", protocolVersion);

        McpModel.ClientInfo clientInfo;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> clientInfoMap = (Map<String, Object>) params.get("clientInfo");
            @SuppressWarnings("unchecked")
            Map<String, Object> capabilitiesMap = (Map<String, Object>) params.get("capabilities");

            clientInfo = new McpModel.ClientInfo(
                    protocolVersion,
                    parseClientCapabilities(capabilitiesMap),
                    parseClientMetadata(clientInfoMap)
            );
        } catch (Exception e) {
            return JsonRpc.Response.error(request.id(),
                    JsonRpc.JsonRpcError.invalidParams("Invalid client info"));
        }

        McpModel.InitializeResult result = initialize(clientInfo).join();
        return JsonRpc.Response.success(request.id(), serializeInitializeResult(result));
    }

    private JsonRpc.Response handlePing(JsonRpc.Request request) {
        return JsonRpc.Response.success(request.id(), Map.of("pong", true));
    }

    private JsonRpc.Response handleListResources(JsonRpc.Request request) {
        List<McpModel.Resource> resources = listResources();
        return JsonRpc.Response.success(request.id(),
                Map.of("resources", serializeResources(resources)));
    }

    private JsonRpc.Response handleListResourceTemplates(JsonRpc.Request request) {
        List<McpModel.ResourceTemplate> templates = listResourceTemplates();
        return JsonRpc.Response.success(request.id(),
                Map.of("resourceTemplates", serializeResourceTemplates(templates)));
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleReadResource(JsonRpc.Request request) {
        Map<String, Object> params = request.params();
        String uriStr = (String) params.get("uri");

        try {
            URI uri = URI.create(uriStr);
            McpModel.ResourceContent content = readResource(uri).join();
            return JsonRpc.Response.success(request.id(),
                    Map.of(
                            "contents", List.of(Map.of(
                                    "uri", uri.toString(),
                                    "mimeType", content.mimeType(),
                                    "text", content.text()
                            ))
                    ));
        } catch (Exception e) {
            return JsonRpc.Response.error(request.id(),
                    JsonRpc.JsonRpcError.invalidParams(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleSubscribeResource(JsonRpc.Request request) {
        Map<String, Object> params = request.params();
        String uriStr = (String) params.get("uri");

        try {
            URI uri = URI.create(uriStr);
            subscribeToResource(uri).join();
            return JsonRpc.Response.success(request.id(), Map.of());
        } catch (Exception e) {
            return JsonRpc.Response.error(request.id(),
                    JsonRpc.JsonRpcError.invalidParams(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleUnsubscribeResource(JsonRpc.Request request) {
        Map<String, Object> params = request.params();
        String uriStr = (String) params.get("uri");

        try {
            URI uri = URI.create(uriStr);
            unsubscribeFromResource(uri).join();
            return JsonRpc.Response.success(request.id(), Map.of());
        } catch (Exception e) {
            return JsonRpc.Response.error(request.id(),
                    JsonRpc.JsonRpcError.invalidParams(e.getMessage()));
        }
    }

    private JsonRpc.Response handleListTools(JsonRpc.Request request) {
        List<McpModel.Tool> tools = listTools();
        return JsonRpc.Response.success(request.id(),
                Map.of("tools", serializeTools(tools)));
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleCallTool(JsonRpc.Request request) {
        Map<String, Object> params = request.params();
        String name = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        try {
            McpModel.ToolArguments toolArgs = new McpModel.ToolArguments(name, arguments);
            McpModel.ToolResult result = callTool(toolArgs).join();

            return JsonRpc.Response.success(request.id(),
                    Map.of("content", serializeToolContent(result.content()),
                            "isError", result.isError()));
        } catch (Exception e) {
            log.error("Tool call error: {}", name, e);
            return JsonRpc.Response.success(request.id(),
                    Map.of(
                            "content", List.of(Map.of(
                                    "type", "text",
                                    "text", "Error: " + e.getMessage()
                            )),
                            "isError", true
                    ));
        }
    }

    private JsonRpc.Response handleListPrompts(JsonRpc.Request request) {
        List<McpModel.Prompt> prompts = listPrompts();
        return JsonRpc.Response.success(request.id(),
                Map.of("prompts", serializePrompts(prompts)));
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleGetPrompt(JsonRpc.Request request) {
        Map<String, Object> params = request.params();
        String name = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        try {
            McpModel.PromptResult result = getPrompt(name, arguments != null ? arguments : Map.of()).join();
            return JsonRpc.Response.success(request.id(),
                    Map.of(
                            "description", result.description(),
                            "messages", serializePromptMessages(result.messages())
                    ));
        } catch (Exception e) {
            return JsonRpc.Response.error(request.id(),
                    JsonRpc.JsonRpcError.invalidParams(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------------
    // SERIALIZATION HELPERS
    // ---------------------------------------------------------------------------

    private McpModel.ClientCapabilities parseClientCapabilities(Map<String, Object> map) {
        return new McpModel.ClientCapabilities(
                map.containsKey("roots"),
                map.containsKey("sampling")
        );
    }

    private McpModel.ClientMetadata parseClientMetadata(Map<String, Object> map) {
        return new McpModel.ClientMetadata(
                (String) map.get("name"),
                (String) map.get("version")
        );
    }

    private Map<String, Object> serializeInitializeResult(McpModel.InitializeResult result) {
        return Map.of(
                "protocolVersion", result.protocolVersion(),
                "capabilities", serializeServerCapabilities(result.capabilities()),
                "serverInfo", Map.of(
                        "name", result.serverInfo().name(),
                        "version", result.serverInfo().version()
                )
        );
    }

    private Map<String, Object> serializeServerCapabilities(McpModel.ServerCapabilities capabilities) {
        Map<String, Object> result = new ConcurrentHashMap<>();
        if (capabilities.resources() != null) {
            result.put("resources", Map.of(
                    "subscribe", capabilities.resources().subscribe(),
                    "listChanged", capabilities.resources().listChanged()
            ));
        }
        if (capabilities.tools() != null) {
            result.put("tools", Map.of(
                    "listChanged", capabilities.tools().listChanged()
            ));
        }
        if (capabilities.prompts() != null) {
            result.put("prompts", Map.of(
                    "listChanged", capabilities.prompts().listChanged()
            ));
        }
        if (capabilities.logging() != null) {
            result.put("logging", Map.of());
        }
        return result;
    }

    private List<Map<String, Object>> serializeResources(List<McpModel.Resource> resources) {
        return resources.stream()
                .map(r -> {
                    Map<String, Object> map = new ConcurrentHashMap<>();
                    map.put("uri", r.uri().toString());
                    map.put("name", r.name());
                    map.put("description", r.description());
                    if (r.mimeType() != null) {
                        map.put("mimeType", r.mimeType());
                    }
                    if (r.metadata() != null) {
                        map.put("metadata", r.metadata());
                    }
                    return map;
                })
                .toList();
    }

    private List<Map<String, Object>> serializeResourceTemplates(List<McpModel.ResourceTemplate> templates) {
        return templates.stream()
                .map(t -> {
                    Map<String, Object> map = new ConcurrentHashMap<>();
                    map.put("uriTemplate", t.uriTemplate());
                    map.put("name", t.name());
                    map.put("description", t.description());
                    if (t.mimeType() != null) {
                        map.put("mimeType", t.mimeType());
                    }
                    if (!t.variables().isEmpty()) {
                        map.put("variables", t.variables());
                    }
                    return map;
                })
                .toList();
    }

    private List<Map<String, Object>> serializeTools(List<McpModel.Tool> tools) {
        return tools.stream()
                .map(t -> Map.of(
                        "name", t.name(),
                        "description", t.description(),
                        "inputSchema", t.inputSchema()
                ))
                .toList();
    }

    private List<Map<String, Object>> serializeToolContent(List<McpModel.ToolContent> content) {
        return content.stream()
                .map(c -> {
                    Map<String, Object> map = new ConcurrentHashMap<>();
                    map.put("type", c.type());
                    if (c.text() != null) {
                        map.put("text", c.text());
                    }
                    if (c.data() != null) {
                        map.put("data", c.data());
                    }
                    if (c.uri() != null) {
                        map.put("uri", c.uri().toString());
                    }
                    return map;
                })
                .toList();
    }

    private List<Map<String, Object>> serializePrompts(List<McpModel.Prompt> prompts) {
        return prompts.stream()
                .map(p -> {
                    Map<String, Object> map = new ConcurrentHashMap<>();
                    map.put("name", p.name());
                    map.put("description", p.description());
                    if (!p.arguments().isEmpty()) {
                        map.put("arguments", p.arguments());
                    }
                    return map;
                })
                .toList();
    }

    private List<Map<String, Object>> serializePromptMessages(List<McpModel.PromptMessage> messages) {
        return messages.stream()
                .map(m -> {
                    Map<String, Object> map = new ConcurrentHashMap<>();
                    map.put("role", m.role());
                    if (m.content() instanceof String) {
                        map.put("content", Map.of("type", "text", "text", m.content()));
                    } else {
                        map.put("content", m.content());
                    }
                    return map;
                })
                .toList();
    }
}
