package br.com.archflow.agent.memory;

import java.util.Map;

/**
 * Interface genérica para injeção de contexto na memória de execução.
 *
 * <p>Produtos implementam esta interface para carregar contexto adicional
 * no início de cada execução ou no resume de flows. Por exemplo, o VendaX
 * implementa para carregar PlaybookConfig e perfil do vendedor.
 *
 * <p>O motor chama {@link #loadContext} antes de invocar o agente,
 * injetando o resultado em {@code ExecutionContext.variables}.
 *
 * <p>Exemplo de uso:
 * <pre>{@code
 * MemoryProvider vendaxProvider = (tenantId, sessionId) -> {
 *     PlaybookConfig config = playbookService.resolve(tenantId);
 *     VendorProfile profile = vendorService.getProfile(tenantId, sessionId);
 *     return Map.of("playbookConfig", config, "vendorProfile", profile);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface MemoryProvider {

    /**
     * Carrega contexto adicional para uma sessão de um tenant.
     *
     * <p>O mapa retornado é mesclado em {@code ExecutionContext.variables}
     * antes da execução do agente.
     *
     * @param tenantId  ID do tenant
     * @param sessionId ID da sessão
     * @return Mapa de variáveis a injetar no contexto (nunca null)
     */
    Map<String, Object> loadContext(String tenantId, String sessionId);
}
