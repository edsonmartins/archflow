package br.com.archflow.api.agent.invocation;

import java.time.Instant;
import java.util.Optional;

/**
 * Onde o consumidor da fila grava o andamento de cada invocação e a consulta por {@code requestId} o lê.
 *
 * <p>Em memória no perfil de desenvolvimento ({@link InMemoryAgentInvocationResultStore}); em banco com
 * {@code archflow.persistence.jdbc.enabled=true} ({@link JdbcAgentInvocationResultStore}) — o resultado
 * precisa sobreviver ao restart e ser visto de qualquer réplica, como a fila durável.</p>
 */
public interface AgentInvocationResultStore {

    /** Grava (insere ou substitui) o registro do {@code requestId}. */
    void save(AgentInvocationResult result);

    Optional<AgentInvocationResult> find(String requestId);

    /**
     * Apaga os resultados finais terminados antes de {@code cutoff} — a retenção que o consumidor aplica
     * ({@code archflow.agents.invocation.result-retention}). Ninguém apaga um resultado depois de lido, e
     * cada linha guarda a saída inteira do agente: sem isto a tabela cresceria para sempre com um gatilho
     * por minuto. Transitórios ({@code QUEUED}/{@code RUNNING}) nunca saem, porque não têm fim.
     *
     * @return quantos foram apagados
     */
    int purgeFinishedBefore(Instant cutoff);
}
