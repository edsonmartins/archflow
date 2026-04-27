package br.com.archflow.api.mcp.impl;

import br.com.archflow.api.mcp.McpInspectionController;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpPromptArgumentDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpPromptDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpResourceDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpToolDto;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Default implementation that keeps a registry of named
 * {@code McpClient} suppliers. Clients are instantiated on first use
 * and cached per {@code serverName}. A caller (typically a Spring
 * configuration class) populates the registry at startup.
 *
 * <p>The default bean has an empty registry. That is deliberate: the
 * endpoint then returns an empty server list instead of crashing, and
 * only deployments that actually configure MCP servers pay the cost
 * of running them.
 */
public class McpInspectionControllerImpl implements McpInspectionController {

    private final Map<String, Supplier<McpClient>> suppliers;
    private final Map<String, McpClient> cache = new ConcurrentHashMap<>();

    public McpInspectionControllerImpl(Map<String, Supplier<McpClient>> suppliers) {
        this.suppliers = new ConcurrentHashMap<>(suppliers);
    }

    @Override
    public List<String> listServerNames() {
        return new ArrayList<>(suppliers.keySet());
    }

    @Override
    public McpServerIntrospectionDto introspect(String serverName) {
        Supplier<McpClient> supplier = suppliers.get(serverName);
        if (supplier == null) {
            return new McpServerIntrospectionDto(
                    serverName, false, List.of(), List.of(), List.of(),
                    "server not registered");
        }

        McpClient client;
        try {
            client = cache.computeIfAbsent(serverName, k -> supplier.get());
            if (!client.isConnected()) {
                client.connect();
                client.initialize().get();
                client.initialized();
            }
        } catch (Exception e) {
            return new McpServerIntrospectionDto(
                    serverName, false, List.of(), List.of(), List.of(),
                    "connect/initialize failed: " + e.getMessage());
        }

        List<McpToolDto> tools = List.of();
        List<McpPromptDto> prompts = List.of();
        List<McpResourceDto> resources = List.of();
        try {
            tools = client.listTools().get().stream()
                    .map(this::toToolDto).toList();
        } catch (Exception ignored) { /* leave empty */ }
        try {
            prompts = client.listPrompts().get().stream()
                    .map(this::toPromptDto).toList();
        } catch (Exception ignored) { /* leave empty */ }
        try {
            resources = client.listResources().get().stream()
                    .map(this::toResourceDto).toList();
        } catch (Exception ignored) { /* leave empty */ }

        return new McpServerIntrospectionDto(
                serverName, client.isConnected(), tools, prompts, resources, null);
    }

    private McpToolDto toToolDto(McpModel.Tool t) {
        return new McpToolDto(t.name(), t.description(), t.inputSchema());
    }

    private McpPromptDto toPromptDto(McpModel.Prompt p) {
        List<McpPromptArgumentDto> args = p.arguments() == null ? List.of() :
                p.arguments().stream()
                        .map(a -> new McpPromptArgumentDto(a.name(), a.description(), a.required()))
                        .toList();
        return new McpPromptDto(p.name(), p.description(), args);
    }

    private McpResourceDto toResourceDto(McpModel.Resource r) {
        return new McpResourceDto(
                r.uri() != null ? r.uri().toString() : null,
                r.name(),
                r.description(),
                r.mimeType());
    }
}
