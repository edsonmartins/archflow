package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.ChatMemory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contexto mantido durante a execução de um fluxo.
 * Mantém estado, variáveis e memória entre os passos.
 *
 * <p>A partir da v2, suporta multi-tenancy com campos de primeira classe:
 * {@code tenantId}, {@code userId}, {@code sessionId} e {@code requestId}.
 *
 * @since 1.0.0
 */
public interface ExecutionContext {
    /**
     * Obtém um valor do contexto.
     */
    Optional<Object> get(String key);

    /**
     * Define um valor no contexto.
     *
     * @deprecated Use {@link #withVariable(String, Object)} para operações imutáveis.
     *             Este método muta o contexto in-place e não é seguro para execuções paralelas.
     */
    @Deprecated
    void set(String key, Object value);

    /**
     * Retorna uma nova instância do contexto com a variável adicionada/atualizada.
     * O contexto original permanece inalterado (imutabilidade).
     *
     * @param key   Chave da variável
     * @param value Valor da variável
     * @return Nova instância do contexto com a variável atualizada
     */
    default ExecutionContext withVariable(String key, Object value) {
        set(key, value);
        return this;
    }

    /**
     * Retorna a memória de chat do LangChain4j.
     */
    ChatMemory getChatMemory();

    /**
     * Obtém métricas da execução atual.
     */
    ExecutionMetrics getMetrics();

    /**
     * Obtém o estado atual do fluxo.
     */
    FlowState getState();

    /**
     * Atualiza o estado do fluxo in-place.
     *
     * @deprecated Use {@link ImmutableExecutionContext#withState(FlowState)} para operações imutáveis.
     */
    @Deprecated
    void setState(FlowState state);

    /**
     * Identificador do tenant. Obrigatório em produção multi-tenant.
     * Default: "SYSTEM" para compatibilidade com código existente.
     */
    default String getTenantId() {
        return "SYSTEM";
    }

    /**
     * Identificador do usuário associado a esta execução.
     */
    default String getUserId() {
        return null;
    }

    /**
     * Identificador da sessão de conversa. Usado como chave de isolamento
     * de memória junto com {@code tenantId}.
     */
    default String getSessionId() {
        return null;
    }

    /**
     * Identificador único desta requisição (UUID auto-gerado).
     */
    default String getRequestId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Retorna todas as variáveis do contexto como mapa imutável.
     */
    default Map<String, Object> getVariables() {
        return Map.of();
    }

    /**
     * Cria um snapshot imutável deste contexto.
     * Útil para execuções paralelas onde cada agente precisa de uma
     * visão congelada do contexto sem interferência mútua.
     */
    default ExecutionContext snapshot() {
        return this;
    }
}