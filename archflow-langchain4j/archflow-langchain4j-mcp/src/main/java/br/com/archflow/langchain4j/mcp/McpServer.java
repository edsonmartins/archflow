package br.com.archflow.langchain4j.mcp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for an MCP (Model Context Protocol) Server.
 *
 * <p>An MCP server exposes three main capabilities to clients:
 * <ul>
 *   <li><b>Resources:</b> Data/context that can be read by LLMs</li>
 *   <li><b>Tools:</b> Executable functions that LLMs can call</li>
 *   <li><b>Prompts:</b> Templated prompts for LLMs</li>
 * </ul>
 *
 * <h3>Server Lifecycle:</h3>
 * <pre>
 * 1. Client sends "initialize" request
 * 2. Server responds with capabilities
 * 3. Client sends "initialized" notification
 * 4. Server is ready to handle requests
 * </pre>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * McpServer server = new MemoryMcpServer();
 *
 * // Handle initialize
 * CompletableFuture<InitializeResult> init = server.initialize(clientInfo);
 *
 * // List available tools
 * List<Tool> tools = server.listTools();
 *
 * // Call a tool
 * CompletableFuture<ToolResult> result = server.callTool(
 *     new ToolArguments("search", Map.of("query", "Java"))
 * );
 * }</pre>
 *
 * @see <a href="https://modelcontextprotocol.io/specification/">MCP Specification</a>
 */
public interface McpServer {

    /**
     * Server information.
     *
     * @return Server metadata
     */
    McpModel.ServerMetadata getServerInfo();

    /**
     * Server capabilities.
     *
     * @return Server capabilities
     */
    McpModel.ServerCapabilities getCapabilities();

    // ---------------------------------------------------------------------------
    // LIFECYCLE
    // ---------------------------------------------------------------------------

    /**
     * Initialize the server with client information.
     *
     * @param clientInfo Client capabilities and metadata
     * @return Server initialization result
     */
    CompletableFuture<McpModel.InitializeResult> initialize(McpModel.ClientInfo clientInfo);

    /**
     * Called after initialization is complete.
     * This is a notification that the client has successfully initialized.
     */
    void initialized();

    /**
     * Shutdown the server gracefully.
     */
    void shutdown();

    // ---------------------------------------------------------------------------
    // RESOURCES
    // ---------------------------------------------------------------------------

    /**
     * List available resources.
     *
     * @return List of resources
     */
    default List<McpModel.Resource> listResources() {
        return List.of();
    }

    /**
     * List available resource templates.
     *
     * @return List of resource templates
     */
    default List<McpModel.ResourceTemplate> listResourceTemplates() {
        return List.of();
    }

    /**
     * Read a resource by URI.
     *
     * @param uri Resource URI
     * @return Resource content
     */
    default CompletableFuture<McpModel.ResourceContent> readResource(java.net.URI uri) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Resources not supported"));
    }

    /**
     * Subscribe to resource updates (if supported).
     *
     * @param uri Resource URI
     * @return Completion signal
     */
    default CompletableFuture<Void> subscribeToResource(java.net.URI uri) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Resource subscription not supported"));
    }

    /**
     * Unsubscribe from resource updates.
     *
     * @param uri Resource URI
     * @return Completion signal
     */
    default CompletableFuture<Void> unsubscribeFromResource(java.net.URI uri) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Resource subscription not supported"));
    }

    // ---------------------------------------------------------------------------
    // TOOLS
    // ---------------------------------------------------------------------------

    /**
     * List available tools.
     *
     * @return List of tools
     */
    default List<McpModel.Tool> listTools() {
        return List.of();
    }

    /**
     * Call a tool with arguments.
     *
     * @param arguments Tool name and arguments
     * @return Tool execution result
     */
    default CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Tools not supported"));
    }

    /**
     * List all tools (returns empty by default).
     * This is a convenience alias for listTools().
     *
     * @return List of tools
     */
    default List<McpModel.Tool> getTools() {
        return listTools();
    }

    // ---------------------------------------------------------------------------
    // PROMPTS
    // ---------------------------------------------------------------------------

    /**
     * List available prompts.
     *
     * @return List of prompts
     */
    default List<McpModel.Prompt> listPrompts() {
        return List.of();
    }

    /**
     * Get a prompt with arguments.
     *
     * @param name Prompt name
     * @param arguments Prompt arguments
     * @return Prompt result with messages
     */
    default CompletableFuture<McpModel.PromptResult> getPrompt(String name, java.util.Map<String, Object> arguments) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Prompts not supported"));
    }

    /**
     * List all prompts (returns empty by default).
     * This is a convenience alias for listPrompts().
     *
     * @return List of prompts
     */
    default List<McpModel.Prompt> getPrompts() {
        return listPrompts();
    }

    // ---------------------------------------------------------------------------
    // UTILITIES
    // ---------------------------------------------------------------------------

    /**
     * Check if resources are supported.
     *
     * @return true if resources are supported
     */
    default boolean supportsResources() {
        return getCapabilities().resources() != null;
    }

    /**
     * Check if tools are supported.
     *
     * @return true if tools are supported
     */
    default boolean supportsTools() {
        return getCapabilities().tools() != null;
    }

    /**
     * Check if prompts are supported.
     *
     * @return true if prompts are supported
     */
    default boolean supportsPrompts() {
        return getCapabilities().prompts() != null;
    }

    /**
     * Check if resource subscription is supported.
     *
     * @return true if resource subscription is supported
     */
    default boolean supportsResourceSubscription() {
        McpModel.ResourceCapabilities resources = getCapabilities().resources();
        return resources != null && resources.subscribe();
    }
}
