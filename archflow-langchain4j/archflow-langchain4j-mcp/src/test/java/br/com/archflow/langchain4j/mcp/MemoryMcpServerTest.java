package br.com.archflow.langchain4j.mcp;

import br.com.archflow.langchain4j.mcp.server.MemoryMcpServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MemoryMcpServer.
 */
class MemoryMcpServerTest {

    @Test
    void testServerCreation() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        assertEquals("test-server", server.getServerInfo().name());
        assertEquals("1.0.0", server.getServerInfo().version());
        assertTrue(server.supportsResources());
        assertTrue(server.supportsTools());
        assertTrue(server.supportsPrompts());
    }

    @Test
    void testServerCreationWithCapabilities() {
        McpModel.ServerCapabilities capabilities = McpModel.ServerCapabilities.toolsOnly();
        MemoryMcpServer server = new MemoryMcpServer("test-server", capabilities);

        assertEquals("test-server", server.getServerInfo().name());
        assertTrue(server.supportsTools());
        assertFalse(server.supportsResources());
        assertFalse(server.supportsPrompts());
    }

    @Test
    void testAddTool() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.Tool tool = McpModel.Tool.simple("echo", "Echo the input", "message");
        server.addTool(tool);

        assertTrue(server.hasTools());
        assertEquals(1, server.listTools().size());
        assertEquals("echo", server.listTools().get(0).name());
    }

    @Test
    void testAddToolWithHandler() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.Tool tool = McpModel.Tool.simple("echo", "Echo the input", "message");
        server.addTool(tool, args -> McpModel.ToolResult.text("Echo: " + args.get("message")));

        List<McpModel.Tool> tools = server.listTools();
        assertEquals(1, tools.size());
    }

    @Test
    void testCallTool() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.Tool tool = McpModel.Tool.simple("echo", "Echo the input", "message");
        server.addTool(tool, args -> McpModel.ToolResult.text("Echo: " + args.get("message")));

        McpModel.ToolArguments args = new McpModel.ToolArguments("echo", Map.of("message", "Hello"));
        McpModel.ToolResult result = server.callTool(args).get();

        assertFalse(result.isError());
        assertEquals("Echo: Hello", result.content().get(0).text());
    }

    @Test
    void testCallToolNotFound() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.ToolArguments args = new McpModel.ToolArguments("unknown", Map.of());
        McpModel.ToolResult result = server.callTool(args).exceptionally(e -> null).get();

        assertNull(result);
    }

    @Test
    void testToolWithInputSchema() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Search query"
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Max results"
                        )
                ),
                "required", List.of("query")
        );

        McpModel.Tool tool = new McpModel.Tool("search", "Search database", schema);
        server.addTool(tool);

        List<McpModel.Tool> tools = server.listTools();
        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).name());
        assertEquals(schema, tools.get(0).inputSchema());
    }

    @Test
    void testAddResource() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.Resource resource = new McpModel.Resource(
                URI.create("file:///test.txt"),
                "test",
                "Test resource",
                "text/plain"
        );
        server.addResource(resource);

        assertTrue(server.hasResources());
        assertEquals(1, server.listResources().size());
        assertEquals("test", server.listResources().get(0).name());
    }

    @Test
    void testAddResourceWithProvider() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        URI uri = URI.create("file:///test.txt");
        McpModel.Resource resource = new McpModel.Resource(
                uri,
                "test",
                "Test resource",
                "text/plain"
        );

        server.addResource(resource, u -> new McpModel.ResourceContent(
                uri,
                "text/plain",
                "Content here"
        ));

        McpModel.ResourceContent content = server.readResource(uri).get();
        assertEquals("Content here", content.text());
    }

    @Test
    void testAddPrompt() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        List<McpModel.PromptArgument> args = List.of(
                new McpModel.PromptArgument("topic", "Topic", true)
        );

        McpModel.Prompt prompt = new McpModel.Prompt("write", "Write something", args);
        server.addPrompt(prompt);

        assertTrue(server.hasPrompts());
        assertEquals(1, server.listPrompts().size());
        assertEquals("write", server.listPrompts().get(0).name());
    }

    @Test
    void testAddPromptWithHandler() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.Prompt prompt = new McpModel.Prompt("greet", "Greet user");
        server.addPrompt(prompt, args -> new McpModel.PromptResult(
                "Greeting",
                List.of(new McpModel.PromptMessage(
                        McpModel.PromptMessage.ROLE_USER,
                        "Hello " + args.get("name")
                ))
        ));

        McpModel.PromptResult result = server.getPrompt("greet", Map.of("name", "World")).get();
        assertEquals("Greeting", result.description());
        assertEquals("Hello World", result.messages().get(0).content());
    }

    @Test
    void testInitialize() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.ClientInfo clientInfo = new McpModel.ClientInfo(
                McpModel.ClientCapabilities.none(),
                new McpModel.ClientMetadata("test-client")
        );

        McpModel.InitializeResult result = server.initialize(clientInfo).get();

        assertEquals(McpModel.ServerInfo.PROTOCOL_VERSION, result.protocolVersion());
        assertEquals("test-server", result.serverInfo().name());
        assertNotNull(result.capabilities());
    }

    @Test
    void testHandleRequestInitialize() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        JsonRpc.Request request = JsonRpc.Request.create("initialize", Map.of(
                "protocolVersion", McpModel.ServerInfo.PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", "test-client",
                        "version", "1.0.0"
                )
        ));

        JsonRpc.Response response = server.handleRequest(request);

        assertFalse(response.isError());
        assertNotNull(response.result());
    }

    @Test
    void testHandleRequestListTools() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");
        server.addTool(McpModel.Tool.simple("echo", "Echo", "msg"));

        JsonRpc.Request request = JsonRpc.Request.create("tools/list", Map.of());
        JsonRpc.Response response = server.handleRequest(request);

        assertFalse(response.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertNotNull(result.get("tools"));
    }

    @Test
    void testHandleRequestPing() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        JsonRpc.Request request = JsonRpc.Request.create("ping", Map.of());
        JsonRpc.Response response = server.handleRequest(request);

        assertFalse(response.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertEquals(Boolean.TRUE, result.get("pong"));
    }

    @Test
    void testHandleRequestUnknownMethod() {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        JsonRpc.Request request = JsonRpc.Request.create("unknown_method", Map.of());
        JsonRpc.Response response = server.handleRequest(request);

        assertTrue(response.isError());
        assertEquals(JsonRpc.JsonRpcError.Codes.METHOD_NOT_FOUND, response.error().code());
    }

    @Test
    void testMultipleTools() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        // Add tools with handlers
        server.addTool(McpModel.Tool.simple("echo", "Echo", "msg"),
                args -> McpModel.ToolResult.text("Echo: " + args.get("msg")));
        server.addTool(McpModel.Tool.simple("reverse", "Reverse", "msg"),
                args -> McpModel.ToolResult.text(new StringBuilder(args.get("msg").toString()).reverse().toString()));
        server.addTool(McpModel.Tool.simple("uppercase", "Uppercase", "msg"),
                args -> McpModel.ToolResult.text(args.get("msg").toString().toUpperCase()));

        assertEquals(3, server.listTools().size());

        McpModel.ToolResult result1 = server.callTool(
                new McpModel.ToolArguments("echo", Map.of("msg", "test"))
        ).get();

        McpModel.ToolResult result2 = server.callTool(
                new McpModel.ToolArguments("uppercase", Map.of("msg", "test"))
        ).get();

        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals("Echo: test", result1.content().get(0).text());
        assertEquals("TEST", result2.content().get(0).text());
    }

    @Test
    void testSetToolHandler() throws Exception {
        MemoryMcpServer server = new MemoryMcpServer("test-server");

        McpModel.Tool tool = McpModel.Tool.simple("add", "Add numbers", "a", "b");
        server.addTool(tool);

        server.setToolHandler("add", args -> {
            int a = Integer.parseInt(args.get("a").toString());
            int b = Integer.parseInt(args.get("b").toString());
            return McpModel.ToolResult.text(String.valueOf(a + b));
        });

        McpModel.ToolResult result = server.callTool(
                new McpModel.ToolArguments("add", Map.of("a", 5, "b", 3))
        ).get();

        assertEquals("8", result.content().get(0).text());
    }
}
