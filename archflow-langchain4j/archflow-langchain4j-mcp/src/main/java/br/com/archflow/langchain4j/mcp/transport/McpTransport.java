package br.com.archflow.langchain4j.mcp.transport;

import br.com.archflow.langchain4j.mcp.JsonRpc;

import java.util.function.Consumer;

/**
 * Transport layer for MCP (Model Context Protocol) communication.
 *
 * <p>The transport layer handles the low-level communication between
 * MCP clients and servers using JSON-RPC 2.0 messages.</p>
 *
 * <h3>Transport Types:</h3>
 * <ul>
 *   <li><b>STDIO:</b> Standard input/output (most common for local MCP servers)</li>
 *   <li><b>SSE:</b> Server-Sent Events (for HTTP-based communication)</li>
 *   <li><b>WebSocket:</b> WebSocket for bidirectional communication</li>
 * </ul>
 *
 * @see StdioServerTransport
 * @see StdioClientTransport
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Start the transport.
     *
     * @throws java.io.IOException if starting fails
     */
    void start() throws java.io.IOException;

    /**
     * Stop the transport.
     */
    void stop();

    /**
     * Check if transport is active.
     *
     * @return true if active
     */
    boolean isActive();

    /**
     * Send a JSON-RPC message.
     *
     * @param message Message to send
     * @throws java.io.IOException if sending fails
     */
    void send(JsonRpc message) throws java.io.IOException;

    /**
     * Register a handler for incoming messages.
     *
     * @param handler Message handler
     */
    void setMessageHandler(Consumer<JsonRpc> handler);

    /**
     * Register a handler for errors.
     *
     * @param handler Error handler
     */
    void setErrorHandler(Consumer<Throwable> handler);

    /**
     * Close the transport.
     */
    @Override
    void close();
}
