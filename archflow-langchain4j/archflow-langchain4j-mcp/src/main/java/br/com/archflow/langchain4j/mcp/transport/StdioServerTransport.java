package br.com.archflow.langchain4j.mcp.transport;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * STDIO transport for MCP server communication.
 *
 * <p>This transport reads JSON-RPC messages from standard input
 * and writes responses to standard output. This is the most common
 * transport type for local MCP servers.</p>
 *
 * <h3>Message Format:</h3>
 * <p>Each message is a JSON-RPC 2.0 message on its own line.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * StdioServerTransport transport = new StdioServerTransport();
 * transport.setMessageHandler(message -> {
 *     // Handle incoming JSON-RPC message
 *     JsonRpc.Response response = handleRequest(message);
 *     transport.send(response);
 * });
 * transport.start();
 * }</pre>
 *
 * @see McpTransport
 */
public class StdioServerTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioServerTransport.class);

    private final ObjectMapper objectMapper;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ExecutorService executorService;
    private final ConcurrentMap<String, Consumer<JsonRpc>> pendingRequests;

    private Consumer<JsonRpc> messageHandler;
    private Consumer<Throwable> errorHandler;
    private volatile boolean active = false;
    private Thread readerThread;

    /**
     * Create a new STDIO transport using System.in and System.out.
     */
    public StdioServerTransport() {
        this(new BufferedReader(new InputStreamReader(System.in)),
                new PrintWriter(System.out, true));
    }

    /**
     * Create a new STDIO transport with custom streams.
     *
     * @param input Input stream
     * @param output Output stream
     */
    public StdioServerTransport(BufferedReader input, PrintWriter output) {
        this(input, output, new ObjectMapper());
    }

    /**
     * Create a new STDIO transport with custom ObjectMapper.
     *
     * @param input Input stream
     * @param output Output stream
     * @param objectMapper JSON mapper
     */
    public StdioServerTransport(BufferedReader input, PrintWriter output, ObjectMapper objectMapper) {
        this.input = input;
        this.output = output;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-stdio-reader");
            t.setDaemon(true);
            return t;
        });
        this.pendingRequests = new ConcurrentHashMap<>();
    }

    @Override
    public void start() throws IOException {
        if (active) {
            return;
        }

        active = true;
        log.debug("STDIO transport starting");

        // Start reader thread
        readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
        readerThread.setDaemon(false);
        readerThread.start();

        log.debug("STDIO transport started");
    }

    @Override
    public void stop() {
        if (!active) {
            return;
        }

        log.debug("STDIO transport stopping");
        active = false;

        // Interrupt reader thread
        if (readerThread != null) {
            readerThread.interrupt();
        }

        // Shutdown executor
        executorService.shutdown();

        log.debug("STDIO transport stopped");
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void send(JsonRpc message) throws IOException {
        if (!active) {
            throw new IOException("Transport is not active");
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            output.println(json);
            output.flush();
            log.trace("Sent: {}", json);
        } catch (Exception e) {
            throw new IOException("Failed to send message", e);
        }
    }

    @Override
    public void setMessageHandler(Consumer<JsonRpc> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void setErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    /**
     * Read messages from stdin and dispatch to handler.
     */
    private void readLoop() {
        log.debug("Reader thread started");

        try {
            String line;
            while (active && (line = input.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                try {
                    JsonRpc message = parseMessage(line);
                    log.trace("Received: {}", line);

                    if (messageHandler != null) {
                        executorService.submit(() -> {
                            try {
                                messageHandler.accept(message);
                            } catch (Exception e) {
                                handleError(e);
                            }
                        });
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse message: {}", line, e);
                    handleError(e);
                }
            }
        } catch (IOException e) {
            if (active) {
                log.error("Error reading from stdin", e);
                handleError(e);
            }
        } finally {
            log.debug("Reader thread ended");
        }
    }

    /**
     * Parse a JSON message into JsonRpc.
     */
    private JsonRpc parseMessage(String json) throws IOException {
        try {
            // Try to parse as generic map to determine type
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = objectMapper.readValue(json, java.util.Map.class);

            Boolean hasId = map.containsKey("id");
            String method = (String) map.get("method");
            Boolean hasMethod = method != null;

            if (hasId && hasMethod) {
                // Request
                return objectMapper.readValue(json, JsonRpc.Request.class);
            } else if (!hasId && hasMethod) {
                // Notification
                return objectMapper.readValue(json, JsonRpc.Notification.class);
            } else if (hasId && !hasMethod) {
                // Response
                return objectMapper.readValue(json, JsonRpc.Response.class);
            } else {
                throw new IOException("Invalid JSON-RPC message: missing 'id' and 'method'");
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse JSON-RPC message", e);
        }
    }

    private void handleError(Throwable error) {
        if (errorHandler != null) {
            try {
                errorHandler.accept(error);
            } catch (Exception e) {
                log.error("Error in error handler", e);
            }
        } else {
            log.error("No error handler configured", error);
        }
    }

    @Override
    public void close() {
        stop();
        try {
            executorService.shutdownNow();
        } catch (Exception e) {
            log.warn("Error closing executor", e);
        }
    }
}
