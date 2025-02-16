package br.com.archflow.model.flow;

import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.metrics.StepMetrics;

import java.util.List;
import java.util.Optional;

/**
 * Resultado da execução de um passo no fluxo.
 */
public interface StepResult {
    /**
     * Retorna o ID do passo.
     *
     * @return ID do passo executado
     */
    String getStepId();

    /**
     * Retorna o status da execução.
     *
     * @return status do passo
     */
    StepStatus getStatus();

    /**
     * Retorna o resultado da execução, se houver.
     *
     * @return resultado opcional
     */
    Optional<Object> getOutput();

    /**
     * Retorna as métricas da execução.
     *
     * @return métricas coletadas
     */
    StepMetrics getMetrics();

    /**
     * Retorna erros ocorridos, se houver.
     *
     * @return lista de erros
     */
    List<StepError> getErrors();
}