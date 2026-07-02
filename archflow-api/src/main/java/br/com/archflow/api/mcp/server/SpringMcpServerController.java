package br.com.archflow.api.mcp.server;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import br.com.archflow.langchain4j.mcp.McpModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP endpoint over plain JSON-RPC 2.0 HTTP POST ({@code POST /mcp}):
 * exposes the tenant's workflows as MCP tools via {@link WorkflowMcpServer}.
 * Implements the request/response subset of MCP Streamable HTTP — each
 * POST carries one JSON-RPC message and the response is a single JSON
 * body (no SSE stream), which spec-compliant clients accept.
 */
@RestController
@RequestMapping("/mcp")
public class SpringMcpServerController {

    /** Upper bound for a synchronous tool call (a full workflow run). */
    private static final long TOOL_CALL_TIMEOUT_SECONDS = 300;

    private final WorkflowMcpServer server;
    private final ObjectMapper json;

    public SpringMcpServerController(WorkflowMcpServer server, ObjectMapper jackson2ObjectMapper) {
        this.server = server;
        this.json = jackson2ObjectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonRpc.Response> handle(@RequestBody Map<String, Object> message) {
        Object id = message.get("id");
        String method = String.valueOf(message.get("method"));

        // Notifications (no id) get acknowledged with 202 and no body.
        if (id == null) {
            if ("notifications/initialized".equals(method)) {
                server.initialized();
            }
            return ResponseEntity.accepted().build();
        }

        try {
            return ResponseEntity.ok(dispatch(id, method, params(message)));
        } catch (Exception e) {
            return ResponseEntity.ok(JsonRpc.Response.error(id, new JsonRpc.JsonRpcError(
                    JsonRpc.JsonRpcError.Codes.INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.toString(),
                    null)));
        }
    }

    private JsonRpc.Response dispatch(Object id, String method, Map<String, Object> params) throws Exception {
        return switch (method) {
            case "initialize" -> {
                McpModel.ClientInfo clientInfo = json.convertValue(params, McpModel.ClientInfo.class);
                yield JsonRpc.Response.success(id, server.initialize(clientInfo).get(5, TimeUnit.SECONDS));
            }
            case "ping" -> JsonRpc.Response.success(id, Map.of());
            case "tools/list" -> JsonRpc.Response.success(id, Map.of("tools", server.listTools()));
            case "tools/call" -> {
                String name = String.valueOf(params.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                McpModel.ToolResult result = server
                        .callTool(new McpModel.ToolArguments(name, arguments))
                        .get(TOOL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                yield JsonRpc.Response.success(id, result);
            }
            case "resources/list" -> JsonRpc.Response.success(id, Map.of("resources", List.of()));
            case "prompts/list" -> JsonRpc.Response.success(id, Map.of("prompts", List.of()));
            default -> JsonRpc.Response.error(id, new JsonRpc.JsonRpcError(
                    JsonRpc.JsonRpcError.Codes.METHOD_NOT_FOUND,
                    "Method not found: " + method,
                    null));
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> message) {
        return message.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
