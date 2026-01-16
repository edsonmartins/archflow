package br.com.archflow.langchain4j.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * JSON-RPC 2.0 message types for MCP protocol.
 *
 * <p>MCP (Model Context Protocol) uses JSON-RPC 2.0 as its message format.
 * This class provides the base structures for all JSON-RPC messages.</p>
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
public sealed interface JsonRpc permits JsonRpc.Request, JsonRpc.Response, JsonRpc.Notification {

    /**
     * JSON-RPC version, must be "2.0".
     */
    String jsonrpc();

    /**
     * JSON-RPC 2.0 Request.
     *
     * @param id Request identifier (must be unique)
     * @param method Method name to invoke
     * @param params Method parameters (optional)
     */
    record Request(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("method") String method,
            @JsonProperty("params") Map<String, Object> params
    ) implements JsonRpc {

        public Request {
            if (!"2.0".equals(jsonrpc)) {
                throw new IllegalArgumentException("jsonrpc must be '2.0'");
            }
        }

        /**
         * Creates a new request with generated ID.
         */
        public static Request create(String method, Map<String, Object> params) {
            return new Request("2.0", java.util.UUID.randomUUID().toString(), method, params);
        }

        /**
         * Creates a notification-style request (no ID).
         */
        public static Notification createNotification(String method, Map<String, Object> params) {
            return new Notification("2.0", method, params);
        }
    }

    /**
     * JSON-RPC 2.0 Response.
     *
     * @param id Request identifier (matches the request)
     * @param result Result value (if successful)
     * @param error Error object (if failed)
     */
    record Response(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("result") Object result,
            @JsonProperty("error") JsonRpcError error
    ) implements JsonRpc {

        public Response {
            if (!"2.0".equals(jsonrpc)) {
                throw new IllegalArgumentException("jsonrpc must be '2.0'");
            }
        }

        /**
         * Creates a successful response.
         */
        public static Response success(Object id, Object result) {
            return new Response("2.0", id, result, null);
        }

        /**
         * Creates an error response.
         */
        public static Response error(Object id, JsonRpcError error) {
            return new Response("2.0", id, null, error);
        }

        /**
         * Checks if this is an error response.
         */
        public boolean isError() {
            return error != null;
        }
    }

    /**
     * JSON-RPC 2.0 Notification (request without ID).
     *
     * @param method Method name
     * @param params Method parameters (optional)
     */
    record Notification(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("method") String method,
            @JsonProperty("params") Map<String, Object> params
    ) implements JsonRpc {

        public Notification {
            if (!"2.0".equals(jsonrpc)) {
                throw new IllegalArgumentException("jsonrpc must be '2.0'");
            }
        }
    }

    /**
     * JSON-RPC 2.0 Error object.
     *
     * @param code Error code
     * @param message Error message
     * @param data Additional error data (optional)
     */
    record JsonRpcError(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") Object data
    ) {
        /**
         * Standard JSON-RPC error codes.
         */
        public static class Codes {
            public static final int PARSE_ERROR = -32700;
            public static final int INVALID_REQUEST = -32600;
            public static final int METHOD_NOT_FOUND = -32601;
            public static final int INVALID_PARAMS = -32602;
            public static final int INTERNAL_ERROR = -32603;

            /**
             * MCP-specific error codes start at -32000.
             */
            public static final int MCP_ERROR_BASE = -32000;
        }

        public static JsonRpcError parseError(String message) {
            return new JsonRpcError(Codes.PARSE_ERROR, message, null);
        }

        public static JsonRpcError invalidRequest(String message) {
            return new JsonRpcError(Codes.INVALID_REQUEST, message, null);
        }

        public static JsonRpcError methodNotFound(String method) {
            return new JsonRpcError(Codes.METHOD_NOT_FOUND, "Method not found: " + method, null);
        }

        public static JsonRpcError invalidParams(String message) {
            return new JsonRpcError(Codes.INVALID_PARAMS, message, null);
        }

        public static JsonRpcError internalError(String message) {
            return new JsonRpcError(Codes.INTERNAL_ERROR, message, null);
        }

        public static JsonRpcError mcpError(int code, String message) {
            return new JsonRpcError(Codes.MCP_ERROR_BASE + code, message, null);
        }
    }
}
