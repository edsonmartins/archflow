package br.com.archflow.api.linktor.impl;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.linktor.LinktorConfigController;
import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.client.StdioMcpClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Default in-memory implementation, scoped per tenant. Each tenant
 * holds its own Linktor credentials so no tenant can read or overwrite
 * another's API key by mutating the shared bean.
 *
 * <p>Client construction logic mirrors Linktor MCP server's own config:
 * the stdio process is launched with {@code LINKTOR_API_URL},
 * {@code LINKTOR_API_KEY} and {@code LINKTOR_ACCESS_TOKEN} environment
 * variables — credentials are never placed on the command line.
 */
public class LinktorConfigControllerImpl implements LinktorConfigController {

    public static final String SERVER_NAME = "linktor";

    private final LinktorConfigDto initial;
    private final Map<String, LinktorConfigDto> byTenant = new ConcurrentHashMap<>();

    public LinktorConfigControllerImpl(LinktorConfigDto initial) {
        this.initial = initial;
    }

    @Override
    public LinktorConfigDto get() {
        return resolve(TenantContext.currentTenantId()).withMaskedSecrets();
    }

    /**
     * Server-side accessor that returns the unmasked config for the
     * current request's tenant. Consumed by internal components (HTTP
     * client, MCP supplier) that need the real credentials; never
     * exposed through HTTP.
     */
    public LinktorConfigDto rawSnapshot() {
        return resolve(TenantContext.currentTenantId());
    }

    @Override
    public LinktorConfigDto update(LinktorConfigDto incoming) {
        String tenantId = TenantContext.currentTenantId();
        LinktorConfigDto next = byTenant.compute(tenantId, (k, prev) -> {
            LinktorConfigDto previous = prev != null ? prev : initial;
            return new LinktorConfigDto(
                    incoming.enabled(),
                    incoming.apiBaseUrl(),
                    preserveIfMasked(incoming.apiKey(),      previous.apiKey()),
                    preserveIfMasked(incoming.accessToken(), previous.accessToken()),
                    incoming.mcpCommand(),
                    incoming.timeoutSeconds());
        });
        return next.withMaskedSecrets();
    }

    @Override
    public Supplier<McpClient> clientSupplier() {
        // Captured at supplier creation time — the MCP inspection
        // registry invokes the supplier from request scope, so reading
        // the tenant id via TenantContext on each invocation works.
        return () -> {
            LinktorConfigDto c = resolve(TenantContext.currentTenantId());
            if (!c.enabled() || c.mcpCommand() == null || c.mcpCommand().isBlank()) {
                return null;
            }
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

    private LinktorConfigDto resolve(String tenantId) {
        return byTenant.computeIfAbsent(tenantId, k -> initial);
    }

    private static String preserveIfMasked(String incoming, String previous) {
        if (incoming == null || incoming.isBlank()) return previous;
        if (incoming.contains("…")) return previous;
        return incoming;
    }
}
