package br.com.archflow.langchain4j.mcp.transport;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.Duration;
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

    /** Default timeout for {@link #sendRequest(JsonRpc.Request)}. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String[] command;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CompletableFuture<JsonRpc.Response>> pendingRequests;

    private Process process;
    private BufferedReader input;
    private PrintWriter output;
    private Thread readerThread;
    private Thread stderrThread;
    private volatile long requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT.toMillis();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private Consumer<JsonRpc> messageHandler;
    private Consumer<Throwable> errorHandler;

    /**
     * Additional environment variables applied to the subprocess before
     * it is started. {@code null} keeps the parent process environment
     * unchanged. Never exposed outside of this class.
     */
    private java.util.Map<String, String> extraEnvironment;

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

    /**
     * Registers extra environment variables that will be merged on top
     * of the parent process environment when the subprocess starts.
     * Must be called before {@link #start()}; later calls are no-ops
     * once the process has spawned.
     *
     * <p>Used by integrations (e.g. Linktor) that drive an MCP server
     * via credentials that must not appear on the shell command line.
     */
    public void setEnvironment(java.util.Map<String, String> env) {
        this.extraEnvironment = env;
    }

    /**
     * Configures the timeout applied to {@link #sendRequest(JsonRpc.Request)}.
     * When a response does not arrive within this window the returned future
     * is completed exceptionally with a {@link TimeoutException} and the
     * request is removed from the pending map.
     *
     * @param timeout positive timeout; defaults to {@link #DEFAULT_REQUEST_TIMEOUT}
     */
    public void setRequestTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
        this.requestTimeoutMillis = timeout.toMillis();
    }

    @Override
    public void start() throws IOException {
        if (active.get()) {
            return;
        }

        // Commands and environment may come from tenant-supplied configuration —
        // enforce the executable allow-list and env-var block-list before spawning.
        McpCommandPolicy.validateCommand(command);
        McpCommandPolicy.validateEnvironment(extraEnvironment);

        log.debug("Starting MCP server process: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            if (extraEnvironment != null && !extraEnvironment.isEmpty()) {
                java.util.Map<String, String> procEnv = pb.environment();
                for (java.util.Map.Entry<String, String> e : extraEnvironment.entrySet()) {
                    if (e.getKey() == null) continue;
                    if (e.getValue() == null) {
                        procEnv.remove(e.getKey());
                    } else {
                        procEnv.put(e.getKey(), e.getValue());
                    }
                }
            }
            process = pb.start();

            input = new BufferedReader(new InputStreamReader(process.getInputStream()));
            output = new PrintWriter(process.getOutputStream(), true);

            active.set(true);

            // Start reader on a virtual thread (blocking I/O with subprocess)
            readerThread = Thread.ofVirtual().name("mcp-client-reader").start(this::readLoop);

            // Drain stderr on a dedicated daemon thread. If nobody consumes it,
            // a chatty MCP server fills the OS pipe buffer (~64KB) and every
            // write on its side blocks, deadlocking the whole protocol.
            stderrThread = Thread.ofVirtual().name("mcp-client-stderr").start(this::stderrLoop);

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

        // Interrupt reader threads
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
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
        failAllPending(() -> new IOException("Transport closed"));

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
     * <p>The returned future completes exceptionally with a
     * {@link TimeoutException} if no response arrives within the configured
     * {@linkplain #setRequestTimeout(Duration) request timeout} (default
     * {@link #DEFAULT_REQUEST_TIMEOUT}), and with an {@link IOException} if
     * the server process terminates before responding.</p>
     *
     * @param request Request to send
     * @return Future that completes with response
     */
    public CompletableFuture<JsonRpc.Response> sendRequest(JsonRpc.Request request) {
        if (!active.get()) {
            return CompletableFuture.failedFuture(new IOException("Transport is not active"));
        }

        String id = String.valueOf(request.id());
        CompletableFuture<JsonRpc.Response> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            send(request);
        } catch (IOException e) {
            if (pendingRequests.remove(id, future)) {
                future.completeExceptionally(e);
            }
            return future;
        }

        // Timeout guard. Whoever removes the entry from the map (reader,
        // stop(), or this timer) owns completing the future — the
        // remove(id, future) CAS makes the race safe.
        long timeoutMillis = requestTimeoutMillis;
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            if (pendingRequests.remove(id, future)) {
                future.completeExceptionally(new TimeoutException(
                        "MCP request '" + request.method() + "' (id=" + id + ") timed out after "
                                + timeoutMillis + " ms"));
            }
        });

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
            // EOF or process death: nobody will ever answer the in-flight
            // requests, so release every caller blocked on a future.
            failAllPending(() -> new IOException("MCP server process terminated"));
        }
    }

    /**
     * Drain the subprocess stderr so the OS pipe never fills up. Lines are
     * surfaced at debug level, prefixed with the server executable name.
     */
    private void stderrLoop() {
        String prefix = command[0];
        try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = err.readLine()) != null) {
                log.debug("[{} stderr] {}", prefix, line);
            }
        } catch (IOException e) {
            log.debug("Stderr reader for [{}] ended: {}", prefix, e.getMessage());
        }
    }

    /**
     * Completes every pending request exceptionally. Entries are removed with
     * a CAS before completion so this never races with the reader thread or
     * the per-request timeout timer.
     */
    private void failAllPending(java.util.function.Supplier<? extends Throwable> errorSupplier) {
        for (String id : pendingRequests.keySet()) {
            CompletableFuture<JsonRpc.Response> future = pendingRequests.remove(id);
            if (future != null) {
                future.completeExceptionally(errorSupplier.get());
            }
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
