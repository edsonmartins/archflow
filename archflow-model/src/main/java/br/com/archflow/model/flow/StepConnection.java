package br.com.archflow.model.flow;

import java.util.Optional;

/**
 * Representa uma conexão entre dois passos em um fluxo.
 * Define como os passos são conectados e as condições de transição.
 */
public interface StepConnection {
    /**
     * Retorna o ID do passo de origem.
     *
     * @return ID do passo de origem
     */
    String getSourceId();

    /**
     * Retorna o ID do passo de destino.
     *
     * @return ID do passo de destino
     */
    String getTargetId();

    /**
     * Retorna a condição para esta transição, se houver.
     *
     * @return condição opcional para a transição
     */
    Optional<String> getCondition();

    /**
     * Indica se esta é uma conexão de caminho de erro.
     *
     * @return true se for um caminho de erro
     */
    boolean isErrorPath();
}