package br.com.archflow.api.mcp.impl;

import br.com.archflow.api.mcp.McpInspectionController;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpPromptArgumentDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpPromptDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpResourceDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpToolDto;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
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
 *
 * <p>Servidores que respondem por tenant (os de {@code archflow.mcp.servers.<ref>}, que recebem o
 * {@code X-TENANT-ID} em toda chamada) entram por uma fábrica que recebe o tenant da requisição, e o
 * cache passa a ser por {@code (servidor, tenant)}. Inspecionar sem tenant mostraria o catálogo do
 * tenant padrão — o {@code WorkflowMcpServer} responde por ele quando o header falta —, e não o que
 * um nó 'mcp-agent' do tenant de quem olha recebe na execução (RCHFLW-100).</p>
 */
public class McpInspectionControllerImpl implements McpInspectionController {

    private static final Logger log = LoggerFactory.getLogger(McpInspectionControllerImpl.class);

    private final Map<String, Supplier<McpClient>> suppliers;
    private final Map<String, Function<String, McpClient>> porTenant;
    private final Supplier<String> tenantDaRequisicao;
    private final Map<String, McpClient> cache = new ConcurrentHashMap<>();

    public McpInspectionControllerImpl(Map<String, Supplier<McpClient>> suppliers) {
        this(suppliers, Map.of(), () -> null);
    }

    /**
     * @param suppliers servidores de client único (o Linktor)
     * @param porTenant servidores cujo client é criado para um tenant; um nome que também esteja em
     *                  {@code suppliers} é recusado, porque a tela não teria como dizer qual dos dois
     *                  mostra
     * @param tenantDaRequisicao o tenant de quem pede a inspeção
     */
    public McpInspectionControllerImpl(Map<String, Supplier<McpClient>> suppliers,
                                       Map<String, Function<String, McpClient>> porTenant,
                                       Supplier<String> tenantDaRequisicao) {
        for (String nome : porTenant.keySet()) {
            if (suppliers.containsKey(nome)) {
                throw new IllegalArgumentException("MCP server '" + nome + "' registered twice");
            }
        }
        this.suppliers = new ConcurrentHashMap<>(suppliers);
        this.porTenant = new ConcurrentHashMap<>(porTenant);
        this.tenantDaRequisicao = tenantDaRequisicao;
    }

    @Override
    public List<String> listServerNames() {
        Set<String> nomes = new LinkedHashSet<>(suppliers.keySet());
        nomes.addAll(porTenant.keySet());
        return new ArrayList<>(nomes);
    }

    @Override
    public McpServerIntrospectionDto introspect(String serverName) {
        Supplier<McpClient> supplier = suppliers.get(serverName);
        String chave = serverName;
        Function<String, McpClient> fabrica = porTenant.get(serverName);
        if (fabrica != null) {
            String tenant = tenantDaRequisicao.get();
            supplier = () -> fabrica.apply(tenant);
            // '\n' não aparece num ref de propriedade, então a chave não colide entre servidores.
            chave = serverName + "\n" + (tenant == null ? "" : tenant);
        }
        if (supplier == null) {
            return new McpServerIntrospectionDto(
                    serverName, false, List.of(), List.of(), List.of(),
                    "server not registered");
        }

        McpClient client;
        try {
            Supplier<McpClient> criar = supplier;
            client = cache.computeIfAbsent(chave, k -> criar.get());
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
        String erro = null;
        try {
            tools = client.listTools().get().stream()
                    .map(this::toToolDto).toList();
        } catch (Exception e) {
            // As tools são o que o agente usa: engolir a falha mostrava "conectado, 0 tools" para um
            // servidor que recusou a listagem. Prompts e resources continuam tolerados — muitos
            // servidores não implementam esses métodos, e a ausência deles não é defeito.
            erro = "tools/list failed: " + causa(e);
        }
        try {
            prompts = client.listPrompts().get().stream()
                    .map(this::toPromptDto).toList();
        } catch (Exception ignored) { /* leave empty */ }
        try {
            resources = client.listResources().get().stream()
                    .map(this::toResourceDto).toList();
        } catch (Exception ignored) { /* leave empty */ }

        return new McpServerIntrospectionDto(
                serverName, client.isConnected(), tools, prompts, resources, erro);
    }

    /** A mensagem da falha de verdade, sem o invólucro do future. */
    private static String causa(Exception e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
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

    /**
     * Closes every cached MCP client on shutdown. Without this, stdio-based
     * servers leak child processes / file descriptors on every redeploy.
     * Spring invokes this via {@code @PreDestroy}; standalone callers may
     * invoke it directly.
     */
    @PreDestroy
    public void shutdown() {
        cache.forEach((name, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close MCP client {}: {}", name, e.getMessage());
            }
        });
        cache.clear();
    }
}
