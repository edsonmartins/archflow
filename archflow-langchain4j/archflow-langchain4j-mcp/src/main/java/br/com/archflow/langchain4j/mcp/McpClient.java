package br.com.archflow.langchain4j.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Client for connecting to external MCP (Model Context Protocol) servers.
 *
 * <p>An MCP client can connect to an MCP server and invoke its capabilities:
 * <ul>
 *   <li><b>Resources:</b> Read data from the server</li>
 *   <li><b>Tools:</b> Call tools exposed by the server</li>
 *   <li><b>Prompts:</b> Get templated prompts from the server</li>
 * </ul>
 *
 * <h3>Connection Lifecycle:</h3>
 * <pre>
 * 1. Connect to server
 * 2. Send initialize request
 * 3. Send initialized notification
 * 4. Use server capabilities
 * 5. Close connection
 * </pre>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * McpClient client = new StdioMcpServer("python", "server.py");
 *
 * // Connect and initialize
 * client.connect();
 * McpModel.ServerInfo info = client.initialize().get();
 *
 * // Use server tools
 * List<Tool> tools = client.listTools();
 * ToolResult result = client.callTool(
 *     new ToolArguments("search", Map.of("query", "Java"))
 * ).get();
 *
 * // Clean up
 * client.close();
 * }</pre>
 *
 * @see McpServer
 * @see <a href="https://modelcontextprotocol.io/specification/">MCP Specification</a>
 */
public interface McpClient extends AutoCloseable {

    /**
     * Connect to the MCP server.
     *
     * @throws java.io.IOException if connection fails
     */
    void connect() throws java.io.IOException;

    /**
     * Initialize the connection with the server.
     *
     * @return Server information
     */
    CompletableFuture<McpModel.ServerInfo> initialize();

    /**
     * Send initialized notification to complete handshake.
     */
    void initialized();

    /**
     * Check if connected to the server.
     *
     * @return true if connected
     */
    boolean isConnected();

    /**
     * Close the connection to the server.
     */
    @Override
    void close();

    // ---------------------------------------------------------------------------
    // RESOURCES
    // ---------------------------------------------------------------------------

    /**
     * List available resources from the server.
     *
     * @return List of resources
     */
    default CompletableFuture<List<McpModel.Resource>> listResources() {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * List available resource templates from the server.
     *
     * @return List of resource templates
     */
    default CompletableFuture<List<McpModel.ResourceTemplate>> listResourceTemplates() {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Read a resource from the server.
     *
     * @param uri Resource URI
     * @return Resource content
     */
    default CompletableFuture<McpModel.ResourceContent> readResource(URI uri) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Resources not supported"));
    }

    /**
     * Subscribe to resource updates.
     *
     * @param uri Resource URI
     * @return Completion signal
     */
    default CompletableFuture<Void> subscribeToResource(URI uri) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Resource subscription not supported"));
    }

    /**
     * Unsubscribe from resource updates.
     *
     * @param uri Resource URI
     * @return Completion signal
     */
    default CompletableFuture<Void> unsubscribeFromResource(URI uri) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Resource subscription not supported"));
    }

    // ---------------------------------------------------------------------------
    // TOOLS
    // ---------------------------------------------------------------------------

    /**
     * List available tools from the server.
     *
     * @return List of tools
     */
    default CompletableFuture<List<McpModel.Tool>> listTools() {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Call a tool on the server.
     *
     * @param arguments Tool name and arguments
     * @return Tool execution result
     */
    default CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Tools not supported"));
    }

    /**
     * Get all tools from the server (synchronous convenience method).
     *
     * @return List of tools
     * @throws java.util.concurrent.ExecutionException if listing fails
     * @throws InterruptedException if interrupted while waiting
     */
    default List<McpModel.Tool> getTools() throws java.util.concurrent.ExecutionException, InterruptedException {
        return listTools().get();
    }

    /**
     * Call a tool synchronously (convenience method).
     *
     * @param toolName Tool name
     * @param arguments Tool arguments
     * @return Tool result
     * @throws java.util.concurrent.ExecutionException if call fails
     * @throws InterruptedException if interrupted while waiting
     */
    default McpModel.ToolResult callToolSync(String toolName, Map<String, Object> arguments)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        return callTool(new McpModel.ToolArguments(toolName, arguments)).get();
    }

    // ---------------------------------------------------------------------------
    // PROMPTS
    // ---------------------------------------------------------------------------

    /**
     * List available prompts from the server.
     *
     * @return List of prompts
     */
    default CompletableFuture<List<McpModel.Prompt>> listPrompts() {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Get a prompt from the server.
     *
     * @param name Prompt name
     * @param arguments Prompt arguments
     * @return Prompt result
     */
    default CompletableFuture<McpModel.PromptResult> getPrompt(String name, Map<String, Object> arguments) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Prompts not supported"));
    }

    /**
     * Get all prompts from the server (synchronous convenience method).
     *
     * @return List of prompts
     * @throws java.util.concurrent.ExecutionException if listing fails
     * @throws InterruptedException if interrupted while waiting
     */
    default List<McpModel.Prompt> getPrompts() throws java.util.concurrent.ExecutionException, InterruptedException {
        return listPrompts().get();
    }

    // ---------------------------------------------------------------------------
    // SERVER INFO
    // ---------------------------------------------------------------------------

    /**
     * Get server capabilities.
     *
     * @return Server capabilities (cached from initialize)
     */
    McpModel.ServerCapabilities getServerCapabilities();

    /**
     * Get server metadata.
     *
     * @return Server metadata (cached from initialize)
     */
    McpModel.ServerMetadata getServerMetadata();

    /**
     * Check if server supports resources.
     *
     * @return true if resources supported
     */
    default boolean supportsResources() {
        return getServerCapabilities().resources() != null;
    }

    /**
     * Check if server supports tools.
     *
     * @return true if tools supported
     */
    default boolean supportsTools() {
        return getServerCapabilities().tools() != null;
    }

    /**
     * Check if server supports prompts.
     *
     * @return true if prompts supported
     */
    default boolean supportsPrompts() {
        return getServerCapabilities().prompts() != null;
    }
}
