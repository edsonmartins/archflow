package br.com.archflow.langchain4j.mcp.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("McpCommandPolicy")
class McpCommandPolicyTest {

    @AfterEach
    void clearPolicy() {
        System.clearProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY);
    }

    // ========== default allow-list ==========

    @Test
    @DisplayName("default allow-list accepts common MCP launchers by bare name")
    void defaultAllowsCommonLaunchers() {
        assertThatCode(() -> McpCommandPolicy.validateCommand(new String[]{"npx", "-y", "some-server"}))
                .doesNotThrowAnyException();
        assertThatCode(() -> McpCommandPolicy.validateCommand(new String[]{"python3", "server.py"}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("default allow-list rejects arbitrary binaries")
    void defaultRejectsArbitraryBinary() {
        assertThatThrownBy(() -> McpCommandPolicy.validateCommand(new String[]{"/usr/bin/curl", "evil.sh"}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("allow-list");
        assertThatThrownBy(() -> McpCommandPolicy.validateCommand(new String[]{"bash", "-c", "rm -rf /"}))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("bare allow-list name does not match a look-alike path")
    void bareNameDoesNotMatchPath() {
        assertThatThrownBy(() -> McpCommandPolicy.validateCommand(new String[]{"/tmp/evil/python"}))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("empty executable is rejected")
    void emptyExecutableRejected() {
        assertThatThrownBy(() -> McpCommandPolicy.validateCommand(new String[]{" "}))
                .isInstanceOf(SecurityException.class);
    }

    // ========== configured allow-list ==========

    @Test
    @DisplayName("wildcard '*' disables the check")
    void wildcardDisablesCheck() {
        System.setProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY, "*");
        assertThatCode(() -> McpCommandPolicy.validateCommand(new String[]{"/anything/at/all"}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("absolute path entry allows exactly that executable")
    void absolutePathEntryAllowsExactExecutable() {
        System.setProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY, "/opt/mcp/bin/server");
        assertThatCode(() -> McpCommandPolicy.validateCommand(new String[]{"/opt/mcp/bin/server"}))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> McpCommandPolicy.validateCommand(new String[]{"/opt/mcp/bin/other"}))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("configured list replaces the default list")
    void configuredListReplacesDefault() {
        System.setProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY, "deno");
        assertThatCode(() -> McpCommandPolicy.validateCommand(new String[]{"deno", "run", "server.ts"}))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> McpCommandPolicy.validateCommand(new String[]{"npx", "-y", "x"}))
                .isInstanceOf(SecurityException.class);
    }

    // ========== environment block-list ==========

    @Test
    @DisplayName("code-injection environment variables are rejected")
    void blockedEnvironmentVariablesRejected() {
        assertThatThrownBy(() -> McpCommandPolicy.validateEnvironment(Map.of("LD_PRELOAD", "/tmp/x.so")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> McpCommandPolicy.validateEnvironment(Map.of("PATH", "/tmp/evil")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> McpCommandPolicy.validateEnvironment(Map.of("NODE_OPTIONS", "--require evil")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("ordinary environment variables are accepted")
    void ordinaryEnvironmentVariablesAccepted() {
        assertThatCode(() -> McpCommandPolicy.validateEnvironment(
                Map.of("LINKTOR_API_KEY", "secret", "MCP_LOG_LEVEL", "debug")))
                .doesNotThrowAnyException();
        assertThatCode(() -> McpCommandPolicy.validateEnvironment(null))
                .doesNotThrowAnyException();
    }

    // ========== enforcement at the transport ==========

    @Test
    @DisplayName("StdioClientTransport.start refuses a command outside the allow-list")
    void transportRefusesDisallowedCommand() {
        System.setProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY, "npx");
        try (StdioClientTransport transport = new StdioClientTransport("cat")) {
            assertThatThrownBy(transport::start).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    @DisplayName("StdioClientTransport.start refuses a blocked environment variable")
    void transportRefusesBlockedEnvironment() throws IOException {
        System.setProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY, "*");
        try (StdioClientTransport transport = new StdioClientTransport("cat")) {
            transport.setEnvironment(Map.of("LD_PRELOAD", "/tmp/x.so"));
            assertThatThrownBy(transport::start).isInstanceOf(SecurityException.class);
        }
    }
}
