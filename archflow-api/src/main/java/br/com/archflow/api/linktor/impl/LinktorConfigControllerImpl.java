package br.com.archflow.api.linktor.impl;

import br.com.archflow.api.linktor.LinktorConfigController;
import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.client.StdioMcpClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Default in-memory implementation. Holds the config in an
 * {@link AtomicReference} and produces an {@link McpClient} supplier
 * that the MCP inspection registry invokes lazily.
 *
 * <p>Client construction logic mirrors Linktor MCP server's own config:
 * the stdio process is launched with {@code LINKTOR_API_URL},
 * {@code LINKTOR_API_KEY} and {@code LINKTOR_ACCESS_TOKEN} environment
 * variables — credentials are never placed on the command line.
 */
public class LinktorConfigControllerImpl implements LinktorConfigController {

    public static final String SERVER_NAME = "linktor";

    private final AtomicReference<LinktorConfigDto> current;

    public LinktorConfigControllerImpl(LinktorConfigDto initial) {
        this.current = new AtomicReference<>(initial);
    }

    @Override
    public LinktorConfigDto get() {
        return current.get().withMaskedSecrets();
    }

    /**
     * Server-side accessor that returns the unmasked config. Consumed
     * by internal components (HTTP client, MCP supplier) that need the
     * real credentials; never exposed through HTTP.
     */
    public LinktorConfigDto rawSnapshot() {
        return current.get();
    }

    @Override
    public synchronized LinktorConfigDto update(LinktorConfigDto incoming) {
        LinktorConfigDto prev = current.get();
        LinktorConfigDto next = new LinktorConfigDto(
                incoming.enabled(),
                incoming.apiBaseUrl(),
                preserveIfMasked(incoming.apiKey(),      prev.apiKey()),
                preserveIfMasked(incoming.accessToken(), prev.accessToken()),
                incoming.mcpCommand(),
                incoming.timeoutSeconds());
        current.set(next);
        return next.withMaskedSecrets();
    }

    @Override
    public Supplier<McpClient> clientSupplier() {
        return () -> {
            LinktorConfigDto c = current.get();
            if (!c.enabled() || c.mcpCommand() == null || c.mcpCommand().isBlank()) {
                return null;
            }
            // Split on whitespace so a caller can write e.g. "node dist/index.js"
            // or a single path to the linktor-mcp binary.
            String[] argv = c.mcpCommand().trim().split("\\s+");
            StdioMcpClient client = new StdioMcpClient(argv);
            Map<String, String> env = new LinkedHashMap<>();
            if (c.apiBaseUrl()  != null && !c.apiBaseUrl().isBlank())  env.put("LINKTOR_API_URL",      c.apiBaseUrl());
            if (c.apiKey()      != null && !c.apiKey().isBlank())      env.put("LINKTOR_API_KEY",      c.apiKey());
            if (c.accessToken() != null && !c.accessToken().isBlank()) env.put("LINKTOR_ACCESS_TOKEN", c.accessToken());
            env.put("LINKTOR_TIMEOUT", String.valueOf(c.timeoutSeconds() * 1000));
            client.getTransport().setEnvironment(env);
            return client;
        };
    }

    private static String preserveIfMasked(String incoming, String previous) {
        if (incoming == null || incoming.isBlank()) return previous;
        if (incoming.contains("…")) return previous;
        return incoming;
    }
}
