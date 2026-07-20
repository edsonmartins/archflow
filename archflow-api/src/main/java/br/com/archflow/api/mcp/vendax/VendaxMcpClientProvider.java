package br.com.archflow.api.mcp.vendax;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.client.HttpMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public VendaxMcpClientProvider(String defaultBaseUrl, String defaultServiceToken) {
        this.defaults = new VendaxConfig(defaultBaseUrl, defaultServiceToken);
    }

    /** Aplica/atualiza a config do VendaX Core para um tenant e invalida o client cacheado. */
    public void configure(String tenantId, VendaxConfig config) {
        byTenant.put(tenantId, config);
        McpClient stale = clients.remove(tenantId);
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
        return client;
    }
}
