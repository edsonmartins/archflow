package br.com.archflow.langchain4j.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * Fake MCP server main class used only by integration tests for
 * {@link br.com.archflow.langchain4j.mcp.client.StdioMcpClient}. It reads
 * JSON-RPC requests from stdin, one per line, and writes matching responses
 * to stdout.
 *
 * <p>Kept in the test sources and launched via
 * {@code java -cp <test-classpath> br.com.archflow.langchain4j.mcp.transport.FakeMcpServerMain}
 * so tests don't depend on python or shell scripting.
 */
public final class FakeMcpServerMain {

    private FakeMcpServerMain() {}

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(line, Map.class);
            Object id = msg.get("id");
            String method = (String) msg.get("method");

            if ("notifications/initialized".equals(method) || id == null) {
                continue; // notifications receive no response
            }

            Map<String, Object> resp = buildResponse(id, method);
            System.out.println(mapper.writeValueAsString(resp));
            System.out.flush();
        }
    }

    private static Map<String, Object> buildResponse(Object id, String method) {
        return switch (method) {
            case "initialize" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of(
                            "protocolVersion", "2025-06-18",
                            "capabilities", Map.of(
                                    "resources", Map.of("subscribe", true, "listChanged", false),
                                    "tools", Map.of("listChanged", false),
                                    "prompts", Map.of("listChanged", false)),
                            "serverInfo", Map.of("name", "fake-server", "version", "0.1.0")));
            case "tools/list" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("tools", List.of(Map.of(
                            "name", "search",
                            "description", "Search the world",
                            "inputSchema", Map.of("type", "object")))));
            case "tools/call" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of(
                            "content", List.of(Map.of("type", "text", "text", "result-text")),
                            "isError", false));
            case "resources/list" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("resources", List.of(Map.of(
                            "uri", "file:///test.txt",
                            "name", "test.txt",
                            "description", "A test file",
                            "mimeType", "text/plain"))));
            case "resources/read" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("contents", List.of(Map.of(
                            "uri", "file:///test.txt",
                            "mimeType", "text/plain",
                            "text", "hello world"))));
            case "resources/templates/list" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("resourceTemplates", List.of(Map.of(
                            "uriTemplate", "file:///{path}",
                            "name", "file-template",
                            "description", "File template",
                            "mimeType", "text/plain",
                            "variables", List.of(Map.of(
                                    "name", "path",
                                    "description", "File path",
                                    "required", true,
                                    "type", "string"))))));
            case "resources/subscribe", "resources/unsubscribe" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of());
            case "prompts/list" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("prompts", List.of(Map.of(
                            "name", "greet",
                            "description", "Greet the user",
                            "arguments", List.of(Map.of(
                                    "name", "who",
                                    "description", "Who to greet",
                                    "required", true))))));
            case "prompts/get" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of(
                            "description", "A greeting",
                            "messages", List.of(Map.of("role", "user", "content", "Hello"))));
            case "error/test" -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "error", Map.of("code", -32601, "message", "fake-error"));
            default -> Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "error", Map.of("code", -32601, "message", "Unknown method: " + method));
        };
    }
}
