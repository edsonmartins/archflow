package br.com.archflow.langchain4j.mcp.transport;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * STDIO transport for MCP client communication.
 *
 * <p>This transport spawns a subprocess and communicates with it
 * via stdin/stdout using JSON-RPC 2.0 messages.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * StdioClientTransport transport = new StdioClientTransport("python", "server.py");
 * transport.setMessageHandler(message -> {
 *     // Handle incoming JSON-RPC message
 *     log.info("Received: {}", message);
 * });
 * transport.start();
 * transport.send(request);
 * }</pre>
 *
 * @see McpTransport
 */
public class StdioClientTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioClientTransport.class);

    private final String[] command;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CompletableFuture<JsonRpc.Response>> pendingRequests;

    private Process process;
    private BufferedReader input;
    private PrintWriter output;
    private Thread readerThread;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private Consumer<JsonRpc> messageHandler;
    private Consumer<Throwable> errorHandler;

    /**
     * Create a new STDIO client transport.
     *
     * @param command Command to execute
     */
    public StdioClientTransport(String... command) {
        this(command, new ObjectMapper());
    }

    /**
     * Create a new STDIO client transport with custom ObjectMapper.
     *
     * @param command Command to execute
     * @param objectMapper JSON mapper
     */
    public StdioClientTransport(String[] command, ObjectMapper objectMapper) {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Command cannot be null or empty");
        }
        this.command = command;
        this.objectMapper = objectMapper;
        this.pendingRequests = new ConcurrentHashMap<>();
    }

    @Override
    public void start() throws IOException {
        if (active.get()) {
            return;
        }

        log.debug("Starting MCP server process: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();

            input = new BufferedReader(new InputStreamReader(process.getInputStream()));
            output = new PrintWriter(process.getOutputStream(), true);

            active.set(true);

            // Start reader thread
            readerThread = new Thread(this::readLoop, "mcp-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            log.debug("MCP server process started, PID: {}", process.pid());

        } catch (IOException e) {
            active.set(false);
            throw new IOException("Failed to start MCP server process", e);
        }
    }

    @Override
    public void stop() {
        if (!active.get()) {
            return;
        }

        log.debug("Stopping MCP server process");
        active.set(false);

        // Interrupt reader thread
        if (readerThread != null) {
            readerThread.interrupt();
        }

        // Destroy process
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn("Process did not exit gracefully, forcing");
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        // Fail all pending requests
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new IOException("Transport closed")));
        pendingRequests.clear();

        log.debug("MCP server process stopped");
    }

    @Override
    public boolean isActive() {
        return active.get() && process != null && process.isAlive();
    }

    @Override
    public void send(JsonRpc message) throws IOException {
        if (!active.get()) {
            throw new IOException("Transport is not active");
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            output.println(json);
            if (output.checkError()) {
                throw new IOException("Error writing to process stdin");
            }
            log.trace("Sent: {}", json);
        } catch (Exception e) {
            throw new IOException("Failed to send message", e);
        }
    }

    /**
     * Send a request and wait for response.
     *
     * @param request Request to send
     * @return Future that completes with response
     */
    public CompletableFuture<JsonRpc.Response> sendRequest(JsonRpc.Request request) {
        if (!active.get()) {
            return CompletableFuture.failedFuture(new IOException("Transport is not active"));
        }

        CompletableFuture<JsonRpc.Response> future = new CompletableFuture<>();
        pendingRequests.put(String.valueOf(request.id()), future);

        try {
            send(request);
        } catch (IOException e) {
            pendingRequests.remove(String.valueOf(request.id()));
            future.completeExceptionally(e);
        }

        return future;
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
     * Read messages from process stdout and dispatch to handler.
     */
    private void readLoop() {
        log.debug("Client reader thread started");

        try {
            String line;
            while (active.get() && (line = input.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                try {
                    JsonRpc message = parseMessage(line);
                    log.trace("Received: {}", line);

                    // Handle response (complete pending request)
                    if (message instanceof JsonRpc.Response response) {
                        CompletableFuture<JsonRpc.Response> future =
                                pendingRequests.remove(String.valueOf(response.id()));
                        if (future != null) {
                            future.complete(response);
                        }
                    }

                    // Dispatch to handler
                    if (messageHandler != null) {
                        try {
                            messageHandler.accept(message);
                        } catch (Exception e) {
                            log.error("Error in message handler", e);
                        }
                    }

                } catch (Exception e) {
                    log.warn("Failed to parse message: {}", line, e);
                    handleError(e);
                }
            }
        } catch (IOException e) {
            if (active.get()) {
                log.error("Error reading from process", e);
                handleError(e);
            }
        } finally {
            log.debug("Client reader thread ended");
            active.set(false);
        }
    }

    /**
     * Parse a JSON message into JsonRpc.
     */
    private JsonRpc parseMessage(String json) throws IOException {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = objectMapper.readValue(json, java.util.Map.class);

            Boolean hasId = map.containsKey("id");
            String method = (String) map.get("method");
            Boolean hasMethod = method != null;

            if (hasId && hasMethod) {
                return objectMapper.readValue(json, JsonRpc.Request.class);
            } else if (!hasId && hasMethod) {
                return objectMapper.readValue(json, JsonRpc.Notification.class);
            } else if (hasId && !hasMethod) {
                return objectMapper.readValue(json, JsonRpc.Response.class);
            } else {
                throw new IOException("Invalid JSON-RPC message");
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
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Get the process ID of the spawned subprocess.
     *
     * @return Process ID or 0 if not running
     */
    public long getPid() {
        return process != null ? process.pid() : 0;
    }
}
