package br.com.archflow.langchain4j.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MCP protocol data structures.
 */
class McpProtocolTest {

    @Test
    void testJsonRpcRequest() {
        JsonRpc.Request request = JsonRpc.Request.create(
                "tools/list",
                Map.of()
        );

        assertEquals("2.0", request.jsonrpc());
        assertEquals("tools/list", request.method());
        assertNotNull(request.id());
        assertTrue(request.id() instanceof String);
    }

    @Test
    void testJsonRpcNotification() {
        JsonRpc.Notification notification = JsonRpc.Request.createNotification(
                "notifications/initialized",
                Map.of()
        );

        assertEquals("2.0", notification.jsonrpc());
        assertEquals("notifications/initialized", notification.method());
        assertNotNull(notification.params());
    }

    @Test
    void testJsonRpcResponseSuccess() {
        Object result = Map.of("tools", List.of());
        JsonRpc.Response response = JsonRpc.Response.success("req-1", result);

        assertEquals("2.0", response.jsonrpc());
        assertEquals("req-1", response.id());
        assertEquals(result, response.result());
        assertFalse(response.isError());
        assertNull(response.error());
    }

    @Test
    void testJsonRpcResponseError() {
        JsonRpc.JsonRpcError error = JsonRpc.JsonRpcError.methodNotFound("unknown_method");
        JsonRpc.Response response = JsonRpc.Response.error("req-1", error);

        assertEquals("2.0", response.jsonrpc());
        assertEquals("req-1", response.id());
        assertNull(response.result());
        assertTrue(response.isError());
        assertEquals(error, response.error());
    }

    @Test
    void testJsonRpcErrorCodes() {
        assertEquals(-32700, JsonRpc.JsonRpcError.Codes.PARSE_ERROR);
        assertEquals(-32600, JsonRpc.JsonRpcError.Codes.INVALID_REQUEST);
        assertEquals(-32601, JsonRpc.JsonRpcError.Codes.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpc.JsonRpcError.Codes.INVALID_PARAMS);
        assertEquals(-32603, JsonRpc.JsonRpcError.Codes.INTERNAL_ERROR);
        assertEquals(-32000, JsonRpc.JsonRpcError.Codes.MCP_ERROR_BASE);
    }

    @Test
    void testResource() {
        McpModel.Resource resource = new McpModel.Resource(
                URI.create("file:///test.txt"),
                "test",
                "Test resource",
                "text/plain"
        );

        assertEquals(URI.create("file:///test.txt"), resource.uri());
        assertEquals("test", resource.name());
        assertEquals("Test resource", resource.description());
        assertEquals("text/plain", resource.mimeType());
        assertNull(resource.metadata());
    }

    @Test
    void testResourceContent() {
        McpModel.ResourceContent content = new McpModel.ResourceContent(
                URI.create("file:///test.txt"),
                "text/plain",
                "Hello, World!"
        );

        assertEquals("Hello, World!", content.text());
        assertTrue(content.isText());
        assertFalse(content.isBlob());
    }

    @Test
    void testTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string")
                )
        );

        McpModel.Tool tool = new McpModel.Tool(
                "search",
                "Search functionality",
                schema
        );

        assertEquals("search", tool.name());
        assertEquals("Search functionality", tool.description());
        assertEquals(schema, tool.inputSchema());
    }

    @Test
    void testToolSimple() {
        McpModel.Tool tool = McpModel.Tool.simple(
                "echo",
                "Echo the input",
                "message"
        );

        assertEquals("echo", tool.name());
        assertEquals("Echo the input", tool.description());
        assertNotNull(tool.inputSchema());

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.inputSchema();
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("message"));
    }

    @Test
    void testToolArguments() {
        Map<String, Object> args = Map.of("query", "Java");
        McpModel.ToolArguments arguments = new McpModel.ToolArguments("search", args);

        assertEquals("search", arguments.name());
        assertEquals(args, arguments.arguments());
    }

    @Test
    void testToolResult() {
        McpModel.ToolResult result = McpModel.ToolResult.text("Hello!");

        assertFalse(result.isError());
        assertEquals(1, result.content().size());
        assertEquals("text", result.content().get(0).type());
        assertEquals("Hello!", result.content().get(0).text());
    }

    @Test
    void testToolResultError() {
        McpModel.ToolResult result = McpModel.ToolResult.error("Something went wrong");

        assertTrue(result.isError());
        assertEquals(1, result.content().size());
        assertEquals("text", result.content().get(0).type());
        assertEquals("Something went wrong", result.content().get(0).text());
    }

    @Test
    void testPrompt() {
        List<McpModel.PromptArgument> args = List.of(
                new McpModel.PromptArgument("topic", "Topic to write about", true)
        );

        McpModel.Prompt prompt = new McpModel.Prompt(
                "write_email",
                "Write an email",
                args
        );

        assertEquals("write_email", prompt.name());
        assertEquals("Write an email", prompt.description());
        assertEquals(1, prompt.arguments().size());
        assertEquals("topic", prompt.arguments().get(0).name());
    }

    @Test
    void testPromptResult() {
        List<McpModel.PromptMessage> messages = List.of(
                new McpModel.PromptMessage(McpModel.PromptMessage.ROLE_USER, "Write a test")
        );

        McpModel.PromptResult result = new McpModel.PromptResult(
                "Generate a test",
                messages
        );

        assertEquals("Generate a test", result.description());
        assertEquals(1, result.messages().size());
        assertEquals(McpModel.PromptMessage.ROLE_USER, result.messages().get(0).role());
    }

    @Test
    void testServerCapabilities() {
        McpModel.ServerCapabilities capabilities = McpModel.ServerCapabilities.all();

        assertNotNull(capabilities.resources());
        assertNotNull(capabilities.tools());
        assertNotNull(capabilities.prompts());
        assertNotNull(capabilities.logging());
    }

    @Test
    void testServerCapabilitiesToolsOnly() {
        McpModel.ServerCapabilities capabilities = McpModel.ServerCapabilities.toolsOnly();

        assertNull(capabilities.resources());
        assertNotNull(capabilities.tools());
        assertNull(capabilities.prompts());
        assertNull(capabilities.logging());
    }

    @Test
    void testServerMetadata() {
        McpModel.ServerMetadata metadata = new McpModel.ServerMetadata("test-server");

        assertEquals("test-server", metadata.name());
        assertEquals("1.0.0", metadata.version());
    }

    @Test
    void testClientCapabilities() {
        McpModel.ClientCapabilities capabilities = McpModel.ClientCapabilities.none();

        assertNull(capabilities.roots());
        assertNull(capabilities.sampling());
    }

    @Test
    void testServerInfo() {
        McpModel.ServerCapabilities capabilities = McpModel.ServerCapabilities.toolsOnly();
        McpModel.ServerMetadata metadata = new McpModel.ServerMetadata("test-server");

        McpModel.ServerInfo info = new McpModel.ServerInfo(capabilities, metadata);

        assertEquals(McpModel.ServerInfo.PROTOCOL_VERSION, info.protocolVersion());
        assertEquals(capabilities, info.capabilities());
        assertEquals(metadata, info.serverInfo());
    }

    @Test
    void testToolContentTypes() {
        McpModel.ToolContent text = new McpModel.ToolContent("Hello");
        assertTrue(text.isText());
        assertFalse(text.isImage());
        assertFalse(text.isResource());

        McpModel.ToolContent image = new McpModel.ToolContent("image", null, "base64data", null);
        assertFalse(image.isText());
        assertTrue(image.isImage());
        assertFalse(image.isResource());

        McpModel.ToolContent resource = new McpModel.ToolContent("resource", null, null, URI.create("file:///test"));
        assertFalse(resource.isText());
        assertFalse(resource.isImage());
        assertTrue(resource.isResource());
    }
}
