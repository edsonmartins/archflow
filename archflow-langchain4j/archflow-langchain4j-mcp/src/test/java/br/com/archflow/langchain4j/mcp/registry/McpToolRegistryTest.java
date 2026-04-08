package br.com.archflow.langchain4j.mcp.registry;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolRegistry")
class McpToolRegistryTest {

    @Mock
    private McpClient client;

    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpToolRegistry();
    }

    private void stubConnectedClientWithTools(McpClient client, McpModel.Tool... tools) {
        when(client.isConnected()).thenReturn(true);
        when(client.listTools()).thenReturn(CompletableFuture.completedFuture(List.of(tools)));
    }

    private McpModel.Tool tool(String name, String description) {
        return McpModel.Tool.simple(name, description);
    }

    @Nested
    @DisplayName("server registration")
    class ServerRegistration {

        @Test
        @DisplayName("should register server and discover tools")
        void shouldRegisterServer() {
            stubConnectedClientWithTools(client, tool("search", "Search tool"));

            registry.registerServer("srv1", client);

            assertThat(registry.hasServer("srv1")).isTrue();
            assertThat(registry.getServerIds()).containsExactly("srv1");
            assertThat(registry.getServer("srv1")).isSameAs(client);
        }

        @Test
        @DisplayName("should reject duplicate server id")
        void shouldRejectDuplicate() {
            stubConnectedClientWithTools(client, tool("t1", "Tool 1"));
            registry.registerServer("srv1", client);

            assertThatThrownBy(() -> registry.registerServer("srv1", client))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already registered");
        }

        @Test
        @DisplayName("should unregister server and remove its tools")
        void shouldUnregisterServer() {
            stubConnectedClientWithTools(client, tool("t1", "Tool"));
            registry.registerServer("srv1", client);

            registry.unregisterServer("srv1");

            assertThat(registry.hasServer("srv1")).isFalse();
            assertThat(registry.getAllTools()).isEmpty();
            verify(client).close();
        }

        @Test
        @DisplayName("should handle unregister of unknown server gracefully")
        void shouldHandleUnknownUnregister() {
            assertThatNoException().isThrownBy(() -> registry.unregisterServer("unknown"));
        }

        @Test
        @DisplayName("should skip tool discovery when not connected")
        void shouldSkipDiscoveryWhenNotConnected() {
            when(client.isConnected()).thenReturn(false);

            registry.registerServer("srv1", client);

            assertThat(registry.hasServer("srv1")).isTrue();
            assertThat(registry.getAllTools()).isEmpty();
            verify(client, never()).listTools();
        }
    }

    @Nested
    @DisplayName("tool access")
    class ToolAccess {

        @Test
        @DisplayName("should return all tools from all servers")
        void shouldReturnAllTools() {
            stubConnectedClientWithTools(client, tool("t1", "Tool 1"), tool("t2", "Tool 2"));
            registry.registerServer("srv1", client);

            var tools = registry.getAllTools();

            assertThat(tools).hasSize(2);
        }

        @Test
        @DisplayName("should find tool by qualified name")
        void shouldFindByQualifiedName() {
            stubConnectedClientWithTools(client, tool("search", "Search"));
            registry.registerServer("srv1", client);

            var descriptor = registry.findTool("srv1:search");

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.serverId()).isEqualTo("srv1");
            assertThat(descriptor.name()).isEqualTo("search");
            assertThat(descriptor.qualifiedName()).isEqualTo("srv1:search");
            assertThat(descriptor.description()).isEqualTo("Search");
        }

        @Test
        @DisplayName("should return null for unknown tool")
        void shouldReturnNullForUnknown() {
            assertThat(registry.findTool("srv1:missing")).isNull();
        }

        @Test
        @DisplayName("should check tool existence")
        void shouldCheckExistence() {
            stubConnectedClientWithTools(client, tool("t1", "Tool"));
            registry.registerServer("srv1", client);

            assertThat(registry.hasTool("srv1:t1")).isTrue();
            assertThat(registry.hasTool("srv1:missing")).isFalse();
        }

        @Test
        @DisplayName("should get tools by server id")
        void shouldGetToolsByServer() {
            stubConnectedClientWithTools(client, tool("t1", "Tool 1"), tool("t2", "Tool 2"));
            registry.registerServer("srv1", client);

            var tools = registry.getTools("srv1");

            assertThat(tools).hasSize(2);
        }

        @Test
        @DisplayName("should search tools by pattern")
        void shouldSearchByPattern() {
            stubConnectedClientWithTools(client, tool("file_search", "Search files"), tool("calculator", "Math"));
            registry.registerServer("srv1", client);

            var results = registry.searchTools("search");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).name()).isEqualTo("file_search");
        }

        @Test
        @DisplayName("should search tools case-insensitively")
        void shouldSearchCaseInsensitive() {
            stubConnectedClientWithTools(client, tool("FileSearch", "Search"));
            registry.registerServer("srv1", client);

            assertThat(registry.searchTools("filesearch")).hasSize(1);
            assertThat(registry.searchTools("FILESEARCH")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("tool execution")
    class ToolExecution {

        @Test
        @DisplayName("should call tool via client")
        void shouldCallTool() throws Exception {
            stubConnectedClientWithTools(client, tool("search", "Search"));
            registry.registerServer("srv1", client);

            var expectedResult = new McpModel.ToolResult(List.of(), false);
            when(client.callTool(any())).thenReturn(CompletableFuture.completedFuture(expectedResult));

            var result = registry.callTool("srv1:search", Map.of("query", "test"));

            assertThat(result).isSameAs(expectedResult);
        }

        @Test
        @DisplayName("should throw when calling unknown tool")
        void shouldThrowForUnknownTool() {
            assertThatThrownBy(() -> registry.callTool("srv1:missing", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tool not found");
        }

        @Test
        @DisplayName("should call tool async")
        void shouldCallToolAsync() throws Exception {
            stubConnectedClientWithTools(client, tool("search", "Search"));
            registry.registerServer("srv1", client);

            var expectedResult = new McpModel.ToolResult(List.of(), false);
            when(client.callTool(any())).thenReturn(CompletableFuture.completedFuture(expectedResult));

            var result = registry.callToolAsync("srv1:search", Map.of("q", "test")).get();

            assertThat(result).isSameAs(expectedResult);
        }

        @Test
        @DisplayName("should return failed future for unknown tool async")
        void shouldReturnFailedFutureForUnknown() {
            var future = registry.callToolAsync("srv1:missing", Map.of());

            assertThat(future).isCompletedExceptionally();
        }
    }

    @Nested
    @DisplayName("listeners")
    class ListenerTests {

        @Test
        @DisplayName("should notify listeners on tool changes")
        void shouldNotifyListeners() {
            var listener = mock(McpToolRegistry.McpToolRegistryListener.class);
            registry.addListener(listener);

            stubConnectedClientWithTools(client, tool("t1", "Tool"));
            registry.registerServer("srv1", client);

            verify(listener).onToolsChanged(registry);
        }

        @Test
        @DisplayName("should not fail if listener throws")
        void shouldNotFailOnListenerError() {
            var badListener = mock(McpToolRegistry.McpToolRegistryListener.class);
            doThrow(new RuntimeException("boom")).when(badListener).onToolsChanged(any());
            registry.addListener(badListener);

            stubConnectedClientWithTools(client, tool("t1", "Tool"));

            assertThatNoException().isThrownBy(() -> registry.registerServer("srv1", client));
        }

        @Test
        @DisplayName("should remove listener")
        void shouldRemoveListener() {
            var listener = mock(McpToolRegistry.McpToolRegistryListener.class);
            registry.addListener(listener);
            registry.removeListener(listener);

            stubConnectedClientWithTools(client, tool("t1", "Tool"));
            registry.registerServer("srv1", client);

            verify(listener, never()).onToolsChanged(any());
        }
    }

    @Nested
    @DisplayName("attributes")
    class AttributeTests {

        @Test
        @DisplayName("should store and retrieve attributes")
        void shouldStoreAttributes() {
            registry.setAttribute("key", "value");

            assertThat(registry.getAttribute("key")).isEqualTo("value");
        }

        @Test
        @DisplayName("should return null for missing attribute")
        void shouldReturnNullForMissing() {
            assertThat(registry.getAttribute("missing")).isNull();
        }

        @Test
        @DisplayName("should return typed attribute")
        void shouldReturnTypedAttribute() {
            registry.setAttribute("count", 42);

            assertThat(registry.getAttribute("count", Integer.class)).isEqualTo(42);
        }

        @Test
        @DisplayName("should return null for type mismatch")
        void shouldReturnNullForTypeMismatch() {
            registry.setAttribute("count", 42);

            assertThat(registry.getAttribute("count", String.class)).isNull();
        }
    }

    @Nested
    @DisplayName("stats and lifecycle")
    class StatsAndLifecycle {

        @Test
        @DisplayName("should return stats")
        void shouldReturnStats() {
            stubConnectedClientWithTools(client, tool("t1", "Tool"));
            registry.registerServer("srv1", client);

            var stats = registry.getStats();

            assertThat(stats.getServerCount()).isEqualTo(1);
            assertThat(stats.getToolCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return empty stats initially")
        void shouldReturnEmptyStats() {
            var stats = registry.getStats();

            assertThat(stats.serverCount()).isZero();
            assertThat(stats.toolCount()).isZero();
        }

        @Test
        @DisplayName("should close all servers on close")
        void shouldCloseAllServers() {
            stubConnectedClientWithTools(client, tool("t1", "Tool"));
            registry.registerServer("srv1", client);

            registry.close();

            assertThat(registry.getServerIds()).isEmpty();
            assertThat(registry.getAllTools()).isEmpty();
            verify(client).close();
        }
    }

    @Nested
    @DisplayName("refresh")
    class RefreshTests {

        @Test
        @DisplayName("should refresh tools for a server")
        void shouldRefreshTools() {
            stubConnectedClientWithTools(client, tool("t1", "Old Tool"));
            registry.registerServer("srv1", client);

            // Simulate new tools on refresh
            when(client.listTools()).thenReturn(
                    CompletableFuture.completedFuture(List.of(tool("t1", "Old Tool"), tool("t2", "New Tool"))));

            registry.refreshTools("srv1");

            assertThat(registry.getAllTools()).hasSize(2);
        }

        @Test
        @DisplayName("should handle refresh of unknown server gracefully")
        void shouldHandleUnknownRefresh() {
            assertThatNoException().isThrownBy(() -> registry.refreshTools("unknown"));
        }
    }
}
