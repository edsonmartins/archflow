package br.com.archflow.api.mcp.vendax;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.client.HttpMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Fornece um {@link McpClient} HTTP para o VendaX Core, por tenant.
 *
 * <p>Config default vem de properties (`archflow.vendax.mcp.base-url` /
 * `.service-token`); overrides por tenant podem ser aplicados via
 * {@link #configure} (padrão do {@code LinktorConfigControllerImpl}: mapa por
 * tenant). Cada client carrega o bearer de serviço e propaga o cabeçalho
 * `X-TENANT-ID = <tenant>` em toda chamada (feito pelo {@link HttpMcpClient}).
 *
 * <p>Os clients são cacheados por tenant (o {@link HttpMcpClient} é
 * stateless por requisição HTTP e reusa o mesmo {@code HttpClient}); a primeira
 * obtenção faz o handshake `initialize`.
 */
public class VendaxMcpClientProvider {

    private static final Logger log = LoggerFactory.getLogger(VendaxMcpClientProvider.class);

    /** Id lógico do server (uso interno / logs). */
    public static final String SERVER_ID = "vendax";

    public record VendaxConfig(String baseUrl, String serviceToken) {
        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    private final VendaxConfig defaults;
    private final Map<String, VendaxConfig> byTenant = new ConcurrentHashMap<>();
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<CatalogKey, CompletableFuture<List<McpModel.Tool>>> toolCatalogs =
            new ConcurrentHashMap<>();

    private record CatalogKey(String tenantId, String definitionVersion) { }

    public VendaxMcpClientProvider(String defaultBaseUrl, String defaultServiceToken) {
        this.defaults = new VendaxConfig(defaultBaseUrl, defaultServiceToken);
    }

    /** Aplica/atualiza a config do VendaX Core para um tenant e invalida o client cacheado. */
    public void configure(String tenantId, VendaxConfig config) {
        byTenant.put(tenantId, config);
        McpClient stale = clients.remove(tenantId);
        toolCatalogs.keySet().removeIf(key -> key.tenantId().equals(tenantId));
        if (stale != null) {
            stale.close();
        }
    }

    public VendaxConfig configFor(String tenantId) {
        return byTenant.getOrDefault(tenantId, defaults);
    }

    public boolean isConfigured(String tenantId) {
        return configFor(tenantId).isConfigured();
    }

    /**
     * Client conectado (handshake feito) para o tenant. Lança se o VendaX Core
     * não estiver configurado para o tenant nem por default.
     */
    public McpClient clientFor(String tenantId) {
        return clients.computeIfAbsent(tenantId, this::buildAndConnect);
    }

    /** Client cuja listagem de tools é cacheada pela versão da definição entregue pelo Core. */
    public McpClient clientFor(String tenantId, String definitionVersion) {
        McpClient client = clientFor(tenantId);
        String version = definitionVersion == null || definitionVersion.isBlank()
                ? "__embedded__" : definitionVersion;
        return new CatalogCachingClient(client, new CatalogKey(tenantId, version));
    }

    /** Remove somente a instância esperada, sem derrubar uma reconexão concorrente mais nova. */
    public void invalidate(String tenantId, McpClient expected, Throwable failure) {
        if (clients.remove(tenantId, expected)) {
            toolCatalogs.keySet().removeIf(key -> key.tenantId().equals(tenantId));
            log.warn("Client MCP do VendaX invalidado para tenant={} após falha de canal: {}",
                    tenantId, rootMessage(failure));
            expected.close();
        }
    }

    private final class CatalogCachingClient implements McpClient {
        private final McpClient delegate;
        private final CatalogKey key;

        private CatalogCachingClient(McpClient delegate, CatalogKey key) {
            this.delegate = delegate;
            this.key = key;
        }

        @Override public void connect() throws java.io.IOException { delegate.connect(); }
        @Override public CompletableFuture<McpModel.ServerInfo> initialize() { return delegate.initialize(); }
        @Override public void initialized() { delegate.initialized(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public void close() { delegate.close(); }
        @Override public McpModel.ServerCapabilities getServerCapabilities() {
            return delegate.getServerCapabilities();
        }
        @Override public McpModel.ServerMetadata getServerMetadata() {
            return delegate.getServerMetadata();
        }
        @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
            CompletableFuture<List<McpModel.Tool>> future = toolCatalogs.computeIfAbsent(
                    key, ignored -> delegate.listTools().thenApply(List::copyOf));
            future.whenComplete((ignored, failure) -> {
                if (failure != null) toolCatalogs.remove(key, future);
            });
            return future;
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
            return delegate.callTool(arguments);
        }
    }

    private McpClient buildAndConnect(String tenantId) {
        VendaxConfig cfg = configFor(tenantId);
        if (!cfg.isConfigured()) {
            throw new IllegalStateException(
                    "VendaX Core MCP não configurado (archflow.vendax.mcp.base-url) para tenant=" + tenantId);
        }
        HttpMcpClient client = new HttpMcpClient(cfg.baseUrl(), cfg.serviceToken(), tenantId);
        try {
            client.connect();
            client.initialize().get();
            client.initialized();
            log.info("VendaX Core MCP conectado para tenant={} em {}", tenantId, cfg.baseUrl());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao inicializar o VendaX Core MCP para tenant=" + tenantId + ": " + e.getMessage(), e);
        }
        McpClient guarded = new InvalidatingClient(tenantId, client);
        return guarded;
    }

    private final class InvalidatingClient implements McpClient {
        private final String tenantId;
        private final McpClient delegate;

        private InvalidatingClient(String tenantId, McpClient delegate) {
            this.tenantId = tenantId;
            this.delegate = delegate;
        }

        @Override public void connect() throws java.io.IOException { delegate.connect(); }
        @Override public CompletableFuture<McpModel.ServerInfo> initialize() { return delegate.initialize(); }
        @Override public void initialized() { delegate.initialized(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public void close() { delegate.close(); }
        @Override public McpModel.ServerCapabilities getServerCapabilities() {
            return delegate.getServerCapabilities();
        }
        @Override public McpModel.ServerMetadata getServerMetadata() {
            return delegate.getServerMetadata();
        }
        @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
            return guard(delegate.listTools());
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
            return guard(delegate.callTool(arguments));
        }

        private <T> CompletableFuture<T> guard(CompletableFuture<T> operation) {
            return operation.whenComplete((ignored, failure) -> {
                if (failure != null && isChannelFailure(failure)) {
                    invalidate(tenantId, this, failure);
                }
            });
        }
    }

    private static boolean isChannelFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.io.IOException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (current instanceof HttpMcpClient.McpRpcException rpc) {
                int code = rpc.code();
                return code == 401 || code == 403 || code == 408 || code == 429 || code >= 500;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
