package br.com.archflow.langchain4j.mcp.client;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.transport.StdioClientTransport;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP client that connects to an external MCP server via STDIO transport.
 *
 * <p>This client spawns a subprocess (e.g., a Python script) and communicates
 * with it using JSON-RPC 2.0 over stdin/stdout.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * // Connect to a Python MCP server
 * StdioMcpClient client = new StdioMcpClient("python", "server.py");
 *
 * // Connect and initialize
 * client.connect();
 * McpModel.ServerInfo info = client.initialize().get();
 *
 * // Use server tools
 * List<Tool> tools = client.listTools().get();
 * ToolResult result = client.callTool(
 *     new ToolArguments("search", Map.of("query", "Java"))
 * ).get();
 *
 * // Clean up
 * client.close();
 * }</pre>
 *
 * @see McpClient
 */
public class StdioMcpClient implements McpClient {

    private final StdioClientTransport transport;
    private final Map<String, CompletableFuture<JsonRpc.Response>> pendingRequests;

    private McpModel.ServerInfo serverInfo;
    private volatile boolean initialized = false;

    /**
     * Create a new STDIO MCP client.
     *
     * @param command Command and arguments to execute
     */
    public StdioMcpClient(String... command) {
        this.transport = new StdioClientTransport(command);
        this.pendingRequests = new ConcurrentHashMap<>();

        // Set up message handler
        transport.setMessageHandler(this::handleMessage);
    }

    @Override
    public void connect() throws java.io.IOException {
        if (isConnected()) {
            return;
        }

        transport.start();
    }

    @Override
    public CompletableFuture<McpModel.ServerInfo> initialize() {
        if (initialized) {
            return CompletableFuture.completedFuture(serverInfo);
        }

        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IOException("Not connected"));
        }

        Map<String, Object> clientInfo = Map.of(
                "name", "archflow-mcp-client",
                "version", "1.0.0"
        );

        Map<String, Object> clientCapabilities = Map.of(
                "roots", Map.of(),
                "sampling", Map.of()
        );

        Map<String, Object> params = Map.of(
                "protocolVersion", McpModel.ServerInfo.PROTOCOL_VERSION,
                "capabilities", clientCapabilities,
                "clientInfo", clientInfo
        );

        JsonRpc.Request request = JsonRpc.Request.create("initialize", params);

        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Initialize failed: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();

                    serverInfo = parseServerInfo(result);
                    initialized = true;

                    // Send initialized notification
                    initializedNotification();

                    return serverInfo;
                });
    }

    @Override
    public void initialized() {
        // This is called when we receive an initialized notification (not used for client)
    }

    /**
     * Send the initialized notification to complete the handshake.
     */
    private void initializedNotification() {
        Map<String, Object> params = Map.of();
        JsonRpc.Notification notification = JsonRpc.Request.createNotification("notifications/initialized", params);

        try {
            transport.send(notification);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to send initialized notification", e);
        }
    }

    @Override
    public boolean isConnected() {
        return transport.isActive();
    }

    @Override
    public void close() {
        transport.close();
        initialized = false;
    }

    @Override
    public McpModel.ServerCapabilities getServerCapabilities() {
        if (!initialized || serverInfo == null) {
            throw new IllegalStateException("Client not initialized");
        }
        return serverInfo.capabilities();
    }

    @Override
    public McpModel.ServerMetadata getServerMetadata() {
        if (!initialized || serverInfo == null) {
            throw new IllegalStateException("Client not initialized");
        }
        return serverInfo.serverInfo();
    }

    // ---------------------------------------------------------------------------
    // RESOURCES
    // ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<List<McpModel.Resource>> listResources() {
        if (!supportsResources()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support resources"));
        }

        JsonRpc.Request request = JsonRpc.Request.create("resources/list", Map.of());
        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to list resources: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");

                    return resources.stream()
                            .map(this::parseResource)
                            .toList();
                });
    }

    @Override
    public CompletableFuture<List<McpModel.ResourceTemplate>> listResourceTemplates() {
        if (!supportsResources()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support resources"));
        }

        JsonRpc.Request request = JsonRpc.Request.create("resources/templates/list", Map.of());
        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to list resource templates: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> templates = (List<Map<String, Object>>) result.get("resourceTemplates");

                    return templates.stream()
                            .map(this::parseResourceTemplate)
                            .toList();
                });
    }

    @Override
    public CompletableFuture<McpModel.ResourceContent> readResource(URI uri) {
        if (!supportsResources()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support resources"));
        }

        Map<String, Object> params = Map.of("uri", uri.toString());
        JsonRpc.Request request = JsonRpc.Request.create("resources/read", params);

        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to read resource: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> contents = (List<Map<String, Object>>) result.get("contents");

                    if (contents == null || contents.isEmpty()) {
                        throw new RuntimeException("No content returned for resource: " + uri);
                    }

                    return parseResourceContent(contents.get(0));
                });
    }

    @Override
    public CompletableFuture<Void> subscribeToResource(URI uri) {
        if (!supportsResourceSubscription()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support resource subscription"));
        }

        Map<String, Object> params = Map.of("uri", uri.toString());
        JsonRpc.Request request = JsonRpc.Request.create("resources/subscribe", params);

        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to subscribe to resource: " + response.error().message());
                    }
                    return null;
                });
    }

    @Override
    public CompletableFuture<Void> unsubscribeFromResource(URI uri) {
        if (!supportsResourceSubscription()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support resource subscription"));
        }

        Map<String, Object> params = Map.of("uri", uri.toString());
        JsonRpc.Request request = JsonRpc.Request.create("resources/unsubscribe", params);

        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to unsubscribe from resource: " + response.error().message());
                    }
                    return null;
                });
    }

    /**
     * Check if server supports resource subscription.
     *
     * @return true if resource subscription is supported
     */
    public boolean supportsResourceSubscription() {
        McpModel.ResourceCapabilities resources = getServerCapabilities().resources();
        return resources != null && resources.subscribe();
    }

    // ---------------------------------------------------------------------------
    // TOOLS
    // ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<List<McpModel.Tool>> listTools() {
        if (!supportsTools()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support tools"));
        }

        JsonRpc.Request request = JsonRpc.Request.create("tools/list", Map.of());
        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to list tools: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

                    return tools.stream()
                            .map(this::parseTool)
                            .toList();
                });
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        if (!supportsTools()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support tools"));
        }

        Map<String, Object> params = Map.of(
                "name", arguments.name(),
                "arguments", arguments.arguments()
        );

        JsonRpc.Request request = JsonRpc.Request.create("tools/call", params);

        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        return McpModel.ToolResult.error("Call failed: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
                    Boolean isError = (Boolean) result.get("isError");
                    boolean error = Boolean.TRUE.equals(isError);

                    return new McpModel.ToolResult(
                            content.stream()
                                    .map(this::parseToolContent)
                                    .toList(),
                            error
                    );
                });
    }

    // ---------------------------------------------------------------------------
    // PROMPTS
    // ---------------------------------------------------------------------------

    @Override
    public CompletableFuture<List<McpModel.Prompt>> listPrompts() {
        if (!supportsPrompts()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support prompts"));
        }

        JsonRpc.Request request = JsonRpc.Request.create("prompts/list", Map.of());
        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to list prompts: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");

                    return prompts.stream()
                            .map(this::parsePrompt)
                            .toList();
                });
    }

    @Override
    public CompletableFuture<McpModel.PromptResult> getPrompt(String name, Map<String, Object> arguments) {
        if (!supportsPrompts()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Server does not support prompts"));
        }

        Map<String, Object> params = Map.of(
                "name", name,
                "arguments", arguments != null ? arguments : Map.of()
        );

        JsonRpc.Request request = JsonRpc.Request.create("prompts/get", params);

        return sendRequest(request)
                .thenApply(response -> {
                    if (response.isError()) {
                        throw new RuntimeException("Failed to get prompt: " + response.error().message());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) response.result();

                    String description = (String) result.get("description");

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");

                    return new McpModel.PromptResult(
                            description,
                            messages.stream()
                                    .map(this::parsePromptMessage)
                                    .toList()
                    );
                });
    }

    // ---------------------------------------------------------------------------
    // INTERNAL HELPERS
    // ---------------------------------------------------------------------------

    /**
     * Send a request and wait for response.
     */
    private CompletableFuture<JsonRpc.Response> sendRequest(JsonRpc.Request request) {
        return transport.sendRequest(request);
    }

    /**
     * Handle incoming message (for notifications).
     */
    private void handleMessage(JsonRpc message) {
        if (message instanceof JsonRpc.Notification notification) {
            handleNotification(notification);
        }
    }

    /**
     * Handle incoming notification.
     */
    private void handleNotification(JsonRpc.Notification notification) {
        String method = notification.method();

        switch (method) {
            case "notifications/message":
            case "notifications/resources/list_changed":
            case "notifications/tools/list_changed":
            case "notifications/prompts/list_changed":
                // Notifications can be handled by subclasses
                break;
            default:
                // Unknown notification
                break;
        }
    }

    // ---------------------------------------------------------------------------
    // PARSING HELPERS
    // ---------------------------------------------------------------------------

    private McpModel.ServerInfo parseServerInfo(Map<String, Object> data) {
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilitiesMap = (Map<String, Object>) data.get("capabilities");
        @SuppressWarnings("unchecked")
        Map<String, Object> serverInfoMap = (Map<String, Object>) data.get("serverInfo");

        McpModel.ServerCapabilities capabilities = parseServerCapabilities(capabilitiesMap);
        McpModel.ServerMetadata serverMetadata = parseServerMetadata(serverInfoMap);

        return new McpModel.ServerInfo(
                (String) data.get("protocolVersion"),
                capabilities,
                serverMetadata
        );
    }

    private McpModel.ServerCapabilities parseServerCapabilities(Map<String, Object> map) {
        if (map == null) {
            return McpModel.ServerCapabilities.toolsOnly();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resourcesMap = (Map<String, Object>) map.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> toolsMap = (Map<String, Object>) map.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> promptsMap = (Map<String, Object>) map.get("prompts");

        return new McpModel.ServerCapabilities(
                resourcesMap != null,
                toolsMap != null,
                promptsMap != null,
                map.containsKey("logging")
        );
    }

    private McpModel.ServerMetadata parseServerMetadata(Map<String, Object> map) {
        return new McpModel.ServerMetadata(
                (String) map.get("name"),
                (String) map.get("version")
        );
    }

    private McpModel.Resource parseResource(Map<String, Object> map) {
        return new McpModel.Resource(
                URI.create((String) map.get("uri")),
                (String) map.get("name"),
                (String) map.get("description"),
                (String) map.get("mimeType"),
                (Map<String, Object>) map.get("metadata")
        );
    }

    private McpModel.ResourceTemplate parseResourceTemplate(Map<String, Object> map) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variablesMap = (List<Map<String, Object>>) map.get("variables");

        List<McpModel.ResourceTemplateVariable> variables = List.of();
        if (variablesMap != null) {
            variables = variablesMap.stream()
                    .map(v -> new McpModel.ResourceTemplateVariable(
                            (String) v.get("name"),
                            (String) v.get("description"),
                            (Boolean) v.get("required"),
                            (String) v.get("type")
                    ))
                    .toList();
        }

        return new McpModel.ResourceTemplate(
                (String) map.get("uriTemplate"),
                (String) map.get("name"),
                (String) map.get("description"),
                (String) map.get("mimeType"),
                variables
        );
    }

    private McpModel.ResourceContent parseResourceContent(Map<String, Object> map) {
        return new McpModel.ResourceContent(
                URI.create((String) map.get("uri")),
                (String) map.get("mimeType"),
                (String) map.get("text"),
                (String) map.get("blob")
        );
    }

    private McpModel.Tool parseTool(Map<String, Object> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) map.get("inputSchema");

        return new McpModel.Tool(
                (String) map.get("name"),
                (String) map.get("description"),
                inputSchema
        );
    }

    private McpModel.ToolContent parseToolContent(Map<String, Object> map) {
        String type = (String) map.get("type");
        String text = (String) map.get("text");
        String data = (String) map.get("data");
        String uri = (String) map.get("uri");

        return new McpModel.ToolContent(type, text, data, uri != null ? URI.create(uri) : null);
    }

    private McpModel.Prompt parsePrompt(Map<String, Object> map) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> argumentsMap = (List<Map<String, Object>>) map.get("arguments");

        List<McpModel.PromptArgument> arguments = List.of();
        if (argumentsMap != null) {
            arguments = argumentsMap.stream()
                    .map(a -> new McpModel.PromptArgument(
                            (String) a.get("name"),
                            (String) a.get("description"),
                            (Boolean) a.get("required")
                    ))
                    .toList();
        }

        return new McpModel.Prompt(
                (String) map.get("name"),
                (String) map.get("description"),
                arguments
        );
    }

    private McpModel.PromptMessage parsePromptMessage(Map<String, Object> map) {
        String role = (String) map.get("role");
        Object content = map.get("content");

        return new McpModel.PromptMessage(role, content);
    }

    /**
     * Get the underlying transport.
     *
     * @return Transport
     */
    public StdioClientTransport getTransport() {
        return transport;
    }
}
