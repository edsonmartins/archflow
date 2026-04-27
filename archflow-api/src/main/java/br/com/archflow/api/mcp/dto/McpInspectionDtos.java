package br.com.archflow.api.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * DTOs for exposing tools/prompts/resources of a configured MCP server.
 */
public final class McpInspectionDtos {

    private McpInspectionDtos() {}

    /** One tool published by a server. */
    public record McpToolDto(
            String name,
            String description,
            Map<String, Object> inputSchema) {}

    /** One prompt template. */
    public record McpPromptDto(
            String name,
            String description,
            List<McpPromptArgumentDto> arguments) {}

    public record McpPromptArgumentDto(
            String name,
            String description,
            boolean required) {}

    /** One resource URI. */
    public record McpResourceDto(
            String uri,
            String name,
            String description,
            String mimeType) {}

    /** Aggregate payload of a single server introspection. */
    public record McpServerIntrospectionDto(
            String serverName,
            boolean connected,
            List<McpToolDto> tools,
            List<McpPromptDto> prompts,
            List<McpResourceDto> resources,
            String error) {}
}
