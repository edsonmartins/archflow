package br.com.archflow.langchain4j.mcp.server;

import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.transport.McpTransport;
import br.com.archflow.langchain4j.mcp.transport.StdioServerTransport;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory MCP server for testing and simple use cases.
 *
 * <p>This server stores resources, tools, and prompts in memory
 * and can be used for testing or as a base for custom implementations.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * MemoryMcpServer server = new MemoryMcpServer("MyServer");
 *
 * // Add a tool
 * server.addTool(new Tool(
 *     "echo",
 *     "Echo back the input",
 *     Map.of("type", "object", "properties", Map.of(
 *         "message", Map.of("type", "string")
 *     ))
 * ));
 *
 * // Set tool handler
 * server.setToolHandler("echo", args ->
 *     ToolResult.text("Echo: " + args.get("message"))
 * );
 *
 * // Start with STDIO transport
 * server.startStdio();
 * }</pre>
 */
public class MemoryMcpServer extends AbstractMcpServer {

    private final List<McpModel.Resource> resources;
    private final List<McpModel.ResourceTemplate> resourceTemplates;
    private final List<McpModel.Tool> tools;
    private final List<McpModel.Prompt> prompts;
    private final Map<String, Function<Map<String, Object>, McpModel.ToolResult>> toolHandlers;
    private final Map<String, Function<URI, McpModel.ResourceContent>> resourceProviders;
    private final Map<String, PromptHandler> promptHandlers;
    private final Map<URI, McpModel.ResourceContent> subscribedResources;

    /**
     * Create a memory MCP server with all capabilities.
     *
     * @param name Server name
     */
    public MemoryMcpServer(String name) {
        this(name, McpModel.ServerCapabilities.all());
    }

    /**
     * Create a memory MCP server with specific capabilities.
     *
     * @param name Server name
     * @param capabilities Server capabilities
     */
    public MemoryMcpServer(String name, McpModel.ServerCapabilities capabilities) {
        super(name, capabilities);
        this.resources = new ArrayList<>();
        this.resourceTemplates = new ArrayList<>();
        this.tools = new ArrayList<>();
        this.prompts = new ArrayList<>();
        this.toolHandlers = new ConcurrentHashMap<>();
        this.resourceProviders = new ConcurrentHashMap<>();
        this.promptHandlers = new ConcurrentHashMap<>();
        this.subscribedResources = new ConcurrentHashMap<>();
    }

    // ---------------------------------------------------------------------------
    // RESOURCE MANAGEMENT
    // ---------------------------------------------------------------------------

    /**
     * Add a resource to this server.
     *
     * @param resource Resource to add
     * @return This server
     */
    public MemoryMcpServer addResource(McpModel.Resource resource) {
        this.resources.add(resource);
        return this;
    }

    /**
     * Add a resource with provider.
     *
     * @param resource Resource metadata
     * @param provider Function to provide content
     * @return This server
     */
    public MemoryMcpServer addResource(McpModel.Resource resource,
                                       Function<URI, McpModel.ResourceContent> provider) {
        addResource(resource);
        if (provider != null) {
            resourceProviders.put(resource.uri().toString(), provider);
        }
        return this;
    }

    /**
     * Add a resource template.
     *
     * @param template Template to add
     * @return This server
     */
    public MemoryMcpServer addResourceTemplate(McpModel.ResourceTemplate template) {
        this.resourceTemplates.add(template);
        return this;
    }

    @Override
    public List<McpModel.Resource> listResources() {
        return List.copyOf(resources);
    }

    @Override
    public List<McpModel.ResourceTemplate> listResourceTemplates() {
        return List.copyOf(resourceTemplates);
    }

    @Override
    public CompletableFuture<McpModel.ResourceContent> readResource(URI uri) {
        if (!supportsResources()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Resources not supported"));
        }

        Function<URI, McpModel.ResourceContent> provider = resourceProviders.get(uri.toString());
        if (provider != null) {
            try {
                return CompletableFuture.completedFuture(provider.apply(uri));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        // Check subscribed resources
        McpModel.ResourceContent subscribed = subscribedResources.get(uri);
        if (subscribed != null) {
            return CompletableFuture.completedFuture(subscribed);
        }

        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Resource not found: " + uri));
    }

    @Override
    public CompletableFuture<Void> subscribeToResource(URI uri) {
        if (!supportsResourceSubscription()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Resource subscription not supported"));
        }
        // For in-memory server, subscription just marks as subscribed
        subscribedResources.put(uri, McpModel.ResourceContent.class.cast(subscribedResources.get(uri)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unsubscribeFromResource(URI uri) {
        subscribedResources.remove(uri);
        return CompletableFuture.completedFuture(null);
    }

    // ---------------------------------------------------------------------------
    // TOOL MANAGEMENT
    // ---------------------------------------------------------------------------

    /**
     * Add a tool to this server.
     *
     * @param tool Tool to add
     * @return This server
     */
    public MemoryMcpServer addTool(McpModel.Tool tool) {
        this.tools.add(tool);
        return this;
    }

    /**
     * Add a tool with handler.
     *
     * @param tool Tool metadata
     * @param handler Function to execute the tool
     * @return This server
     */
    public MemoryMcpServer addTool(McpModel.Tool tool,
                                   Function<Map<String, Object>, McpModel.ToolResult> handler) {
        addTool(tool);
        if (handler != null) {
            toolHandlers.put(tool.name(), handler);
        }
        return this;
    }

    /**
     * Set a tool handler.
     *
     * @param toolName Tool name
     * @param handler Handler function
     * @return This server
     */
    public MemoryMcpServer setToolHandler(String toolName,
                                         Function<Map<String, Object>, McpModel.ToolResult> handler) {
        toolHandlers.put(toolName, handler);
        return this;
    }

    @Override
    public List<McpModel.Tool> listTools() {
        return List.copyOf(tools);
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        if (!supportsTools()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Tools not supported"));
        }

        String toolName = arguments.name();
        Function<Map<String, Object>, McpModel.ToolResult> handler = toolHandlers.get(toolName);

        if (handler == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Tool not found: " + toolName));
        }

        try {
            McpModel.ToolResult result = handler.apply(arguments.arguments());
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---------------------------------------------------------------------------
    // PROMPT MANAGEMENT
    // ---------------------------------------------------------------------------

    /**
     * Add a prompt to this server.
     *
     * @param prompt Prompt to add
     * @return This server
     */
    public MemoryMcpServer addPrompt(McpModel.Prompt prompt) {
        this.prompts.add(prompt);
        return this;
    }

    /**
     * Add a prompt with handler.
     *
     * @param prompt Prompt metadata
     * @param handler Handler function
     * @return This server
     */
    public MemoryMcpServer addPrompt(McpModel.Prompt prompt, PromptHandler handler) {
        addPrompt(prompt);
        if (handler != null) {
            promptHandlers.put(prompt.name(), handler);
        }
        return this;
    }

    /**
     * Set a prompt handler.
     *
     * @param promptName Prompt name
     * @param handler Handler function
     * @return This server
     */
    public MemoryMcpServer setPromptHandler(String promptName, PromptHandler handler) {
        promptHandlers.put(promptName, handler);
        return this;
    }

    @Override
    public List<McpModel.Prompt> listPrompts() {
        return List.copyOf(prompts);
    }

    @Override
    public CompletableFuture<McpModel.PromptResult> getPrompt(String name, Map<String, Object> arguments) {
        if (!supportsPrompts()) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Prompts not supported"));
        }

        PromptHandler handler = promptHandlers.get(name);
        if (handler == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Prompt not found: " + name));
        }

        try {
            McpModel.PromptResult result = handler.apply(arguments);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Functional interface for prompt handlers.
     */
    @FunctionalInterface
    public interface PromptHandler extends Function<Map<String, Object>, McpModel.PromptResult> {
        @Override
        McpModel.PromptResult apply(Map<String, Object> arguments);
    }

    // ---------------------------------------------------------------------------
    // TRANSPORT
    // ---------------------------------------------------------------------------

    /**
     * Start the server with STDIO transport.
     *
     * @throws java.io.IOException if transport fails to start
     */
    public void startStdio() throws java.io.IOException {
        McpTransport transport = new StdioServerTransport();
        startWithTransport(transport);
    }

    /**
     * Start the server with a custom transport.
     *
     * @param transport Transport to use
     * @throws java.io.IOException if transport fails to start
     */
    public void startWithTransport(McpTransport transport) throws java.io.IOException {
        setTransport(transport);

        // Set up message handler
        transport.setMessageHandler(message -> {
            if (message instanceof br.com.archflow.langchain4j.mcp.JsonRpc.Request request) {
                br.com.archflow.langchain4j.mcp.JsonRpc.Response response = handleRequest(request);
                try {
                    transport.send(response);
                } catch (java.io.IOException e) {
                    System.err.println("Error sending response: " + e.getMessage());
                }
            } else if (message instanceof br.com.archflow.langchain4j.mcp.JsonRpc.Notification notification) {
                // Handle notifications
                if ("notifications/initialized".equals(notification.method())) {
                    initialized();
                }
            }
        });

        transport.setErrorHandler(error -> {
            System.err.println("Transport error: " + error.getMessage());
        });

        transport.start();
    }

    /**
     * Check if server has any tools registered.
     *
     * @return true if has tools
     */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * Check if server has any resources registered.
     *
     * @return true if has resources
     */
    public boolean hasResources() {
        return !resources.isEmpty();
    }

    /**
     * Check if server has any prompts registered.
     *
     * @return true if has prompts
     */
    public boolean hasPrompts() {
        return !prompts.isEmpty();
    }
}
