package br.com.archflow.langchain4j.mcp.registry;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registry for discovering and managing MCP tools from multiple servers.
 *
 * <p>This registry maintains a collection of tools from multiple MCP servers,
 * allowing for tool discovery and execution across the entire MCP ecosystem.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * McpToolRegistry registry = new McpToolRegistry();
 *
 * // Register a server
 * McpClient client = new StdioMcpClient("python", "server.py");
 * registry.registerServer("server1", client);
 *
 * // Discover all tools
 * List<McpToolDescriptor> tools = registry.getAllTools();
 *
 * // Execute a tool
 * McpModel.ToolResult result = registry.callTool("server1:search", Map.of("query", "Java"));
 * }</pre>
 */
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final Map<String, McpClient> servers;
    private final Map<String, McpToolDescriptor> toolIndex;
    private final List<McpToolRegistryListener> listeners;
    private final Map<String, Object> attributes;

    /**
     * Create a new MCP tool registry.
     */
    public McpToolRegistry() {
        this.servers = new ConcurrentHashMap<>();
        this.toolIndex = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.attributes = new ConcurrentHashMap<>();
    }

    // ---------------------------------------------------------------------------
    // SERVER REGISTRATION
    // ---------------------------------------------------------------------------

    /**
     * Register an MCP server.
     *
     * @param serverId Unique server ID
     * @param client MCP client
     * @throws IllegalArgumentException if serverId already registered
     */
    public void registerServer(String serverId, McpClient client) {
        if (servers.containsKey(serverId)) {
            throw new IllegalArgumentException("Server already registered: " + serverId);
        }

        servers.put(serverId, client);
        log.info("Registered MCP server: {}", serverId);

        // Discover tools from server
        discoverTools(serverId, client);
    }

    /**
     * Unregister an MCP server.
     *
     * @param serverId Server ID to unregister
     */
    public void unregisterServer(String serverId) {
        McpClient client = servers.remove(serverId);
        if (client != null) {
            // Remove tools from this server
            toolIndex.entrySet().removeIf(entry ->
                    entry.getValue().serverId().equals(serverId));

            // Close client
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing client for server: {}", serverId, e);
            }

            log.info("Unregistered MCP server: {}", serverId);
            fireToolsChanged();
        }
    }

    /**
     * Get a registered server.
     *
     * @param serverId Server ID
     * @return MCP client or null if not found
     */
    public McpClient getServer(String serverId) {
        return servers.get(serverId);
    }

    /**
     * Get all registered server IDs.
     *
     * @return List of server IDs
     */
    public List<String> getServerIds() {
        return List.copyOf(servers.keySet());
    }

    /**
     * Check if a server is registered.
     *
     * @param serverId Server ID
     * @return true if registered
     */
    public boolean hasServer(String serverId) {
        return servers.containsKey(serverId);
    }

    // ---------------------------------------------------------------------------
    // TOOL DISCOVERY
    // ---------------------------------------------------------------------------

    /**
     * Discover tools from a server.
     *
     * @param serverId Server ID
     * @param client MCP client
     */
    private void discoverTools(String serverId, McpClient client) {
        if (!client.isConnected()) {
            log.debug("Server {} not connected, skipping tool discovery", serverId);
            return;
        }

        try {
            List<McpModel.Tool> tools = client.listTools().get();

            for (McpModel.Tool tool : tools) {
                String qualifiedName = serverId + ":" + tool.name();
                McpToolDescriptor descriptor = new McpToolDescriptor(
                        qualifiedName,
                        serverId,
                        tool.name(),
                        tool.description(),
                        tool.inputSchema()
                );
                toolIndex.put(qualifiedName, descriptor);
            }

            log.info("Discovered {} tools from server {}", tools.size(), serverId);
            fireToolsChanged();

        } catch (Exception e) {
            log.error("Failed to discover tools from server: {}", serverId, e);
        }
    }

    /**
     * Refresh tool discovery for a server.
     *
     * @param serverId Server ID
     */
    public void refreshTools(String serverId) {
        McpClient client = servers.get(serverId);
        if (client != null) {
            // Remove existing tools from this server
            toolIndex.entrySet().removeIf(entry ->
                    entry.getValue().serverId().equals(serverId));

            // Rediscover
            discoverTools(serverId, client);
        }
    }

    /**
     * Refresh tool discovery for all servers.
     */
    public void refreshAll() {
        servers.keySet().forEach(this::refreshTools);
    }

    // ---------------------------------------------------------------------------
    // TOOL ACCESS
    // ---------------------------------------------------------------------------

    /**
     * Get all tool descriptors.
     *
     * @return List of all tools
     */
    public List<McpToolDescriptor> getAllTools() {
        return List.copyOf(toolIndex.values());
    }

    /**
     * Get tools from a specific server.
     *
     * @param serverId Server ID
     * @return List of tools from server
     */
    public List<McpToolDescriptor> getTools(String serverId) {
        return toolIndex.values().stream()
                .filter(t -> t.serverId().equals(serverId))
                .toList();
    }

    /**
     * Find a tool by qualified name.
     *
     * @param qualifiedName Qualified tool name (e.g., "server1:search")
     * @return Tool descriptor or null if not found
     */
    public McpToolDescriptor findTool(String qualifiedName) {
        return toolIndex.get(qualifiedName);
    }

    /**
     * Search for tools by name pattern.
     *
     * @param pattern Name pattern (case-insensitive)
     * @return List of matching tools
     */
    public List<McpToolDescriptor> searchTools(String pattern) {
        String lowerPattern = pattern.toLowerCase();
        return toolIndex.values().stream()
                .filter(t -> t.name().toLowerCase().contains(lowerPattern) ||
                           t.qualifiedName().toLowerCase().contains(lowerPattern))
                .toList();
    }

    /**
     * Check if a tool exists.
     *
     * @param qualifiedName Qualified tool name
     * @return true if tool exists
     */
    public boolean hasTool(String qualifiedName) {
        return toolIndex.containsKey(qualifiedName);
    }

    // ---------------------------------------------------------------------------
    // TOOL EXECUTION
    // ---------------------------------------------------------------------------

    /**
     * Call a tool by qualified name.
     *
     * @param qualifiedName Qualified tool name (e.g., "server1:search")
     * @param arguments Tool arguments
     * @return Tool result
     * @throws IllegalArgumentException if tool not found
     * @throws java.util.concurrent.ExecutionException if execution fails
     * @throws InterruptedException if interrupted while waiting
     */
    public McpModel.ToolResult callTool(String qualifiedName, Map<String, Object> arguments)
            throws java.util.concurrent.ExecutionException, InterruptedException {

        McpToolDescriptor descriptor = toolIndex.get(qualifiedName);
        if (descriptor == null) {
            throw new IllegalArgumentException("Tool not found: " + qualifiedName);
        }

        McpClient client = servers.get(descriptor.serverId());
        if (client == null) {
            throw new IllegalStateException("Server not available: " + descriptor.serverId());
        }

        McpModel.ToolArguments toolArgs = new McpModel.ToolArguments(descriptor.name(), arguments);
        return client.callTool(toolArgs).get();
    }

    /**
     * Call a tool asynchronously.
     *
     * @param qualifiedName Qualified tool name
     * @param arguments Tool arguments
     * @return Future with tool result
     */
    public java.util.concurrent.CompletableFuture<McpModel.ToolResult> callToolAsync(
            String qualifiedName, Map<String, Object> arguments) {

        McpToolDescriptor descriptor = toolIndex.get(qualifiedName);
        if (descriptor == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Tool not found: " + qualifiedName));
        }

        McpClient client = servers.get(descriptor.serverId());
        if (client == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("Server not available: " + descriptor.serverId()));
        }

        McpModel.ToolArguments toolArgs = new McpModel.ToolArguments(descriptor.name(), arguments);
        return client.callTool(toolArgs);
    }

    // ---------------------------------------------------------------------------
    // LISTENERS
    // ---------------------------------------------------------------------------

    /**
     * Add a registry listener.
     *
     * @param listener Listener to add
     */
    public void addListener(McpToolRegistryListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a registry listener.
     *
     * @param listener Listener to remove
     */
    public void removeListener(McpToolRegistryListener listener) {
        listeners.remove(listener);
    }

    private void fireToolsChanged() {
        for (McpToolRegistryListener listener : listeners) {
            try {
                listener.onToolsChanged(this);
            } catch (Exception e) {
                log.error("Error notifying listener", e);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // ATTRIBUTES
    // ---------------------------------------------------------------------------

    /**
     * Set an attribute on the registry.
     *
     * @param key Attribute key
     * @param value Attribute value
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Get an attribute.
     *
     * @param key Attribute key
     * @return Attribute value or null
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Get an attribute with type.
     *
     * @param key Attribute key
     * @param type Expected type
     * @return Attribute value or null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // STATS
    // ---------------------------------------------------------------------------

    /**
     * Get registry statistics.
     *
     * @return Statistics
     */
    public McpToolRegistryStats getStats() {
        return new McpToolRegistryStats(
                servers.size(),
                toolIndex.size()
        );
    }

    /**
     * Close the registry and all registered servers.
     */
    public void close() {
        List<String> serverIds = List.copyOf(servers.keySet());
        for (String serverId : serverIds) {
            unregisterServer(serverId);
        }
        listeners.clear();
    }

    // ---------------------------------------------------------------------------
    // NESTED CLASSES
    // ---------------------------------------------------------------------------

    /**
     * Descriptor for an MCP tool.
     */
    public record McpToolDescriptor(
            String qualifiedName,
            String serverId,
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {
        /**
         * Get the server ID.
         */
        public String serverId() {
            return serverId;
        }

        /**
         * Get the tool name.
         */
        public String name() {
            return name;
        }

        /**
         * Get the qualified name (server:tool).
         */
        public String qualifiedName() {
            return qualifiedName;
        }

        /**
         * Get the tool description.
         */
        public String description() {
            return description;
        }

        /**
         * Get the input schema.
         */
        public Map<String, Object> inputSchema() {
            return inputSchema;
        }
    }

    /**
     * Listener for registry changes.
     */
    @FunctionalInterface
    public interface McpToolRegistryListener {
        /**
         * Called when tools in the registry change.
         *
         * @param registry The registry
         */
        void onToolsChanged(McpToolRegistry registry);
    }

    /**
     * Registry statistics.
     */
    public record McpToolRegistryStats(
            int serverCount,
            int toolCount
    ) {
        public int getServerCount() {
            return serverCount;
        }

        public int getToolCount() {
            return toolCount;
        }
    }
}
