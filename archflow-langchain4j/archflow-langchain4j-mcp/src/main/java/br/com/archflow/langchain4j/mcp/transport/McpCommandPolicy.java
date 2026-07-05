package br.com.archflow.langchain4j.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Security policy for MCP subprocess execution.
 *
 * <p>MCP server commands can originate from tenant-supplied configuration
 * (e.g. the admin REST API), so the transport must never execute arbitrary
 * binaries. This policy enforces an allow-list of executables and blocks
 * environment variables capable of injecting code into the subprocess
 * (e.g. {@code LD_PRELOAD}, {@code NODE_OPTIONS}).
 *
 * <h3>Configuration</h3>
 * The allow-list is read from the {@value #ALLOWED_COMMANDS_PROPERTY} system
 * property or the {@value #ALLOWED_COMMANDS_ENV} environment variable
 * (property wins), as a comma-separated list:
 * <ul>
 *   <li>a bare name (e.g. {@code npx}) allows exactly that command name —
 *       paths such as {@code /tmp/evil/npx} do NOT match;</li>
 *   <li>an absolute path allows exactly that executable;</li>
 *   <li>the single value {@code *} disables the allow-list entirely
 *       (explicit opt-out, intended for development and tests).</li>
 * </ul>
 * When unset, a default list of common MCP launchers is used:
 * {@code npx, node, python, python3, uvx, uv, docker, java}.
 */
public final class McpCommandPolicy {

    private static final Logger log = LoggerFactory.getLogger(McpCommandPolicy.class);

    public static final String ALLOWED_COMMANDS_PROPERTY = "archflow.mcp.allowed-commands";
    public static final String ALLOWED_COMMANDS_ENV = "ARCHFLOW_MCP_ALLOWED_COMMANDS";

    private static final Set<String> DEFAULT_ALLOWED = Set.of(
            "npx", "node", "python", "python3", "uvx", "uv", "docker", "java");

    /**
     * Environment variables that can alter what code the subprocess runs.
     * Supplying any of these via {@code setEnvironment} is rejected.
     */
    private static final Set<String> BLOCKED_ENV_VARS = Set.of(
            "PATH",
            "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT",
            "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
            "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
            "NODE_OPTIONS",
            "PYTHONSTARTUP", "PYTHONPATH",
            "PERL5OPT", "RUBYOPT");

    private McpCommandPolicy() {
    }

    /**
     * Validates that the executable in {@code command[0]} is allowed.
     *
     * @throws SecurityException when the executable is not on the allow-list
     */
    public static void validateCommand(String[] command) {
        String executable = command[0];
        if (executable == null || executable.isBlank()) {
            throw new SecurityException("MCP command executable is empty");
        }

        Set<String> allowed = resolveAllowedCommands();
        if (allowed.contains("*")) {
            return;
        }

        for (String entry : allowed) {
            boolean entryIsPath = entry.contains("/") || entry.contains("\\");
            if (entryIsPath) {
                if (Path.of(executable).normalize().equals(Path.of(entry).normalize())) {
                    return;
                }
            } else if (executable.equals(entry)) {
                // Bare allow-list names only match bare command names, so a
                // look-alike path (/tmp/evil/python) can never satisfy "python".
                return;
            }
        }

        log.warn("Blocked MCP command not on allow-list: {}", executable);
        throw new SecurityException(
                "MCP command '" + executable + "' is not on the allow-list. Permitted: " + allowed
                + ". Configure via -D" + ALLOWED_COMMANDS_PROPERTY
                + " or " + ALLOWED_COMMANDS_ENV + " (comma-separated; '*' disables the check).");
    }

    /**
     * Validates extra environment variables destined for the subprocess.
     *
     * @throws SecurityException when a variable capable of code injection is present
     */
    public static void validateEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return;
        }
        for (String key : environment.keySet()) {
            if (key != null && BLOCKED_ENV_VARS.contains(key.toUpperCase())) {
                log.warn("Blocked MCP environment variable: {}", key);
                throw new SecurityException(
                        "Environment variable '" + key + "' is not allowed for MCP subprocesses "
                        + "(it can inject code into the spawned server)");
            }
        }
    }

    private static Set<String> resolveAllowedCommands() {
        String configured = System.getProperty(ALLOWED_COMMANDS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ALLOWED_COMMANDS_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ALLOWED;
        }
        Set<String> entries = new LinkedHashSet<>();
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(entries::add);
        return entries.isEmpty() ? DEFAULT_ALLOWED : entries;
    }
}
