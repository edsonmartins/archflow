package br.com.archflow.langchain4j.mcp.client;

import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.transport.FakeMcpServerMain;
import br.com.archflow.langchain4j.mcp.transport.StdioClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StdioMcpClient")
class StdioMcpClientTest {

    private static final String[] SERVER_COMMAND = buildServerCommand();

    private StdioMcpClient client;

    private static String[] buildServerCommand() {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        return new String[]{
                javaBin,
                "-cp", classpath,
                FakeMcpServerMain.class.getName()
        };
    }

    @BeforeEach
    void setUp() {
        client = new StdioMcpClient(SERVER_COMMAND);
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    @DisplayName("isConnected is false before connect")
    void notConnectedInitially() {
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("getServerCapabilities before initialize throws IllegalStateException")
    void capabilitiesBeforeInitializeThrows() {
        assertThatThrownBy(() -> client.getServerCapabilities())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("getServerMetadata before initialize throws IllegalStateException")
    void metadataBeforeInitializeThrows() {
        assertThatThrownBy(() -> client.getServerMetadata())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("initialize before connect fails exceptionally")
    void initializeBeforeConnectFails() {
        CompletableFuture<McpModel.ServerInfo> future = client.initialize();
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    @DisplayName("connect + initialize completes the handshake and caches server info")
    void initializeHandshake() throws Exception {
        client.connect();
        assertThat(client.isConnected()).isTrue();

        McpModel.ServerInfo info = client.initialize().get();

        assertThat(info.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(info.serverInfo().name()).isEqualTo("fake-server");
        assertThat(info.serverInfo().version()).isEqualTo("0.1.0");
        assertThat(info.capabilities()).isNotNull();
        assertThat(info.capabilities().tools()).isNotNull();
        assertThat(info.capabilities().resources()).isNotNull();
        assertThat(info.capabilities().prompts()).isNotNull();

        // Second initialize should be a no-op returning cached info.
        McpModel.ServerInfo again = client.initialize().get();
        assertThat(again).isSameAs(info);

        assertThat(client.getServerCapabilities()).isSameAs(info.capabilities());
        assertThat(client.getServerMetadata()).isSameAs(info.serverInfo());

        assertThat(client.supportsTools()).isTrue();
        assertThat(client.supportsResources()).isTrue();
        assertThat(client.supportsPrompts()).isTrue();
        assertThat(client.supportsResourceSubscription()).isTrue();
    }

    @Test
    @DisplayName("connect is idempotent")
    void connectIdempotent() throws IOException {
        client.connect();
        client.connect(); // must not crash
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    @DisplayName("listTools returns tools from the fake server")
    void listTools() throws Exception {
        connectAndInit();
        List<McpModel.Tool> tools = client.listTools().get();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("search");
        assertThat(tools.get(0).description()).isEqualTo("Search the world");
    }

    @Test
    @DisplayName("callTool returns the server's content")
    void callTool() throws Exception {
        connectAndInit();
        McpModel.ToolResult result = client.callTool(
                new McpModel.ToolArguments("search", Map.of("q", "java"))).get();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).text()).isEqualTo("result-text");
    }

    @Test
    @DisplayName("callToolSync convenience method returns content")
    void callToolSync() throws Exception {
        connectAndInit();
        McpModel.ToolResult result = client.callToolSync("search", Map.of("q", "java"));
        assertThat(result.content().get(0).text()).isEqualTo("result-text");
    }

    @Test
    @DisplayName("getTools convenience method returns the list")
    void getTools() throws Exception {
        connectAndInit();
        assertThat(client.getTools()).hasSize(1);
    }

    @Test
    @DisplayName("listResources returns resources from the fake server")
    void listResources() throws Exception {
        connectAndInit();
        List<McpModel.Resource> resources = client.listResources().get();
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).name()).isEqualTo("test.txt");
        assertThat(resources.get(0).uri().toString()).isEqualTo("file:///test.txt");
    }

    @Test
    @DisplayName("readResource returns the resource content")
    void readResource() throws Exception {
        connectAndInit();
        McpModel.ResourceContent content = client.readResource(URI.create("file:///test.txt")).get();
        assertThat(content.text()).isEqualTo("hello world");
        assertThat(content.mimeType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("listResourceTemplates returns templates")
    void listResourceTemplates() throws Exception {
        connectAndInit();
        List<McpModel.ResourceTemplate> templates = client.listResourceTemplates().get();
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).uriTemplate()).isEqualTo("file:///{path}");
        assertThat(templates.get(0).variables()).hasSize(1);
    }

    @Test
    @DisplayName("subscribeToResource and unsubscribeFromResource complete normally")
    void subscribeUnsubscribe() throws Exception {
        connectAndInit();
        client.subscribeToResource(URI.create("file:///test.txt")).get();
        client.unsubscribeFromResource(URI.create("file:///test.txt")).get();
    }

    @Test
    @DisplayName("listPrompts returns prompts from the fake server")
    void listPrompts() throws Exception {
        connectAndInit();
        List<McpModel.Prompt> prompts = client.listPrompts().get();
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).name()).isEqualTo("greet");
        assertThat(prompts.get(0).arguments()).hasSize(1);
        assertThat(prompts.get(0).arguments().get(0).required()).isTrue();
    }

    @Test
    @DisplayName("getPrompts convenience method returns the list")
    void getPrompts() throws Exception {
        connectAndInit();
        assertThat(client.getPrompts()).hasSize(1);
    }

    @Test
    @DisplayName("getPrompt with arguments returns PromptResult")
    void getPrompt() throws Exception {
        connectAndInit();
        McpModel.PromptResult result = client.getPrompt("greet", Map.of("who", "world")).get();
        assertThat(result.description()).isEqualTo("A greeting");
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role()).isEqualTo("user");
    }

    @Test
    @DisplayName("getPrompt with null arguments defaults to empty map")
    void getPromptNullArgs() throws Exception {
        connectAndInit();
        McpModel.PromptResult result = client.getPrompt("greet", null).get();
        assertThat(result.messages()).hasSize(1);
    }

    @Test
    @DisplayName("close releases transport and resets initialized flag")
    void closeResetsState() throws Exception {
        connectAndInit();
        assertThat(client.isConnected()).isTrue();

        client.close();
        assertThat(client.isConnected()).isFalse();

        // After close, capability accessors should throw again (cache was invalidated).
        assertThatThrownBy(() -> client.getServerCapabilities())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("getTransport returns the underlying transport")
    void getTransport() {
        StdioClientTransport t = client.getTransport();
        assertThat(t).isNotNull();
    }

    @Test
    @DisplayName("initialized() no-op does not crash")
    void initializedNoOp() {
        client.initialized();
    }

    private void connectAndInit() throws IOException, ExecutionException, InterruptedException {
        client.connect();
        client.initialize().get();
    }
}
