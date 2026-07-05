package br.com.archflow.api.mcp.server;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP endpoint over plain JSON-RPC 2.0 HTTP POST ({@code POST /mcp}):
 * exposes the tenant's workflows as MCP tools via {@link WorkflowMcpServer}.
 * Implements the request/response subset of MCP Streamable HTTP — each
 * POST carries one JSON-RPC message and the response is a single JSON
 * body (no SSE stream), which spec-compliant clients accept.
 *
 * <p>Dispatch is delegated to {@code AbstractMcpServer.handleRequest} so the
 * HTTP transport shares the same method table (and ping/error semantics) as
 * every other MCP transport. Because {@code tools/call} runs a whole workflow
 * synchronously, the request is handled on a virtual thread and the servlet
 * thread is released immediately (async Spring MVC), so long tool calls
 * cannot drain Tomcat's platform-thread pool.
 */
@RestController
@RequestMapping("/mcp")
public class SpringMcpServerController {

    /** Upper bound for a synchronous tool call (a full workflow run). */
    private static final long REQUEST_TIMEOUT_SECONDS = 300;

    private final WorkflowMcpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SpringMcpServerController(WorkflowMcpServer server) {
        this.server = server;
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<JsonRpc.Response>> handle(
            @RequestBody Map<String, Object> message) {
        Object id = message.get("id");
        String method = String.valueOf(message.get("method"));

        // Notifications (no id) get acknowledged with 202 and no body.
        if (id == null) {
            if ("notifications/initialized".equals(method)) {
                server.initialized();
            }
            return CompletableFuture.completedFuture(ResponseEntity.accepted().build());
        }

        JsonRpc.Request request;
        try {
            request = new JsonRpc.Request("2.0", id, method, params(message));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(ResponseEntity.ok(
                    JsonRpc.Response.error(id, new JsonRpc.JsonRpcError(
                            JsonRpc.JsonRpcError.Codes.INVALID_REQUEST, e.getMessage(), null))));
        }

        return CompletableFuture
                .supplyAsync(() -> server.handleRequest(request), executor)
                .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(err -> {
                    String detail = err instanceof TimeoutException
                            ? "Request timed out after " + REQUEST_TIMEOUT_SECONDS + "s"
                            : (err.getMessage() != null ? err.getMessage() : err.toString());
                    return JsonRpc.Response.error(id, new JsonRpc.JsonRpcError(
                            JsonRpc.JsonRpcError.Codes.INTERNAL_ERROR, detail, null));
                })
                .thenApply(ResponseEntity::ok);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> message) {
        return message.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
