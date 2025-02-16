package br.com.archflow.model.flow;

import br.com.archflow.core.ExecutionMetrics;
import br.com.archflow.core.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;

import java.util.List;
import java.util.Optional;

/**
 * Resultado da execução de um fluxo.
 * Contém o resultado final e métricas da execução.
 *
 * @since 1.0.0
 */
public interface FlowResult {
    /**
     * Retorna o status final da execução.
     *
     * @return status da execução
     */
    ExecutionStatus getStatus();

    /**
     * Retorna o resultado da execução.
     *
     * @return resultado opcional
     */
    Optional<Object> getOutput();

    /**
     * Retorna métricas da execução.
     *
     * @return métricas coletadas
     */
    ExecutionMetrics getMetrics();

    /**
     * Retorna erros ocorridos durante a execução.
     *
     * @return lista de erros
     */
    List<ExecutionError> getErrors();
}