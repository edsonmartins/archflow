package br.com.archflow.api.linktor;

import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import br.com.archflow.langchain4j.mcp.McpClient;

import java.util.function.Supplier;

/**
 * Runtime controller for Linktor settings. Holds the current config
 * and exposes a supplier that the MCP inspection registry consumes so
 * a freshly-updated config is reflected on the next
 * {@code /api/admin/mcp/servers/linktor} request — no restart needed.
 */
public interface LinktorConfigController {

    /** Returns the current config with secrets masked. */
    LinktorConfigDto get();

    /** Replaces the config atomically. Masked secrets in the payload keep the stored values. */
    LinktorConfigDto update(LinktorConfigDto incoming);

    /**
     * Supplier of a freshly-built {@link McpClient} driven by the
     * current config. Returns {@code null} when Linktor is disabled or
     * {@code mcpCommand} is blank.
     */
    Supplier<McpClient> clientSupplier();
}
