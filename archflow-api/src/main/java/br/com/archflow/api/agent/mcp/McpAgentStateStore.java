package br.com.archflow.api.agent.mcp;

import java.util.List;
import java.util.Optional;

/**
 * Armazena laços de tool-calling suspensos.
 *
 * <p>É o ponto de troca que mantém o laço agnóstico de durabilidade: a
 * implementação em memória serve dev, a JDBC serve produção, e um orquestrador
 * externo (Temporal e afins) entra como uma terceira implementação sem que o
 * {@link McpAgentRunner} mude — ele só sabe salvar e carregar um
 * {@link McpAgentState}.
 */
public interface McpAgentStateStore {

    /** Cria ou substitui o estado suspenso (upsert por {@code runId}). */
    void save(McpAgentState state);

    Optional<McpAgentState> findByRunId(String runId);

    /**
     * Busca pelo id da solicitação de aprovação — é por ele que o decisor chega,
     * e ele não conhece o {@code runId}.
     */
    Optional<McpAgentState> findByRequestId(String requestId);

    /** Remove o estado; chamado quando o laço termina. */
    boolean delete(String runId);

    /** Suspensos de um tenant, para montar a fila de decisões pendentes. */
    List<McpAgentState> findPendingByTenant(String tenantId);
}
