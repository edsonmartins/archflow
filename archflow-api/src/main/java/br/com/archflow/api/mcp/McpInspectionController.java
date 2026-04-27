package br.com.archflow.api.mcp;

import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;

import java.util.List;

/**
 * Admin-facing controller that lets the UI introspect each configured
 * MCP server — listing tools, prompts and resources exposed by it.
 *
 * <p>The implementation is responsible for owning {@code McpClient}
 * lifecycles (connect / initialize / close) and for mapping the
 * registry key ({@code serverName}) to the right client instance. The
 * controller never exposes clients directly; all external access is
 * read-only through these DTOs.
 */
public interface McpInspectionController {

    /** Configured server names. */
    List<String> listServerNames();

    /**
     * Connects (or uses a cached connection) and returns tools, prompts
     * and resources from the server. On failure the returned DTO has
     * {@code connected=false} and {@code error} set.
     */
    McpServerIntrospectionDto introspect(String serverName);
}
