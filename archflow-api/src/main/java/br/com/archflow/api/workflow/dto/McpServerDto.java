package br.com.archflow.api.workflow.dto;

/**
 * Descriptor of a registered MCP server exposed to the workflow editor
 * so users can pick from known servers instead of typing raw commands.
 *
 * @param name       unique server name
 * @param transport  {@code stdio} or {@code sse}
 * @param command    command line for {@code stdio} (null for SSE)
 * @param url        URL for {@code sse} (null for STDIO)
 * @param toolCount  number of tools currently exposed by the server
 */
public record McpServerDto(
        String name,
        String transport,
        String command,
        String url,
        int toolCount
) {}
