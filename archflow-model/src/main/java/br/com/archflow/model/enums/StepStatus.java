package br.com.archflow.model.enums;

/**
 * Define os possíveis estados de um passo durante/após sua execução.
 */
public enum StepStatus {
    /**
     * Passo aguardando execução
     */
    PENDING,

    /**
     * Passo em execução
     */
    RUNNING,

    /**
     * Passo executado com sucesso
     */
    COMPLETED,

    /**
     * Passo falhou durante execução
     */
    FAILED,

    /**
     * Passo foi pulado (ex: condição não atendida)
     */
    SKIPPED,

    /**
     * Passo foi cancelado
     */
    CANCELLED,

    /**
     * Passo está pausado
     */
    PAUSED,

    /**
     * Timeout durante execução do passo
     */
    TIMEOUT;

    /**
     * Verifica se este é um status final (não haverá mais mudanças).
     *
     * @return true se for um status final
     */
    public boolean isFinal() {
        return this == COMPLETED || 
               this == FAILED || 
               this == SKIPPED || 
               this == CANCELLED ||
               this == TIMEOUT;
    }

    /**
     * Verifica se este é um status de erro.
     *
     * @return true se for um status de erro
     */
    public boolean isError() {
        return this == FAILED || this == TIMEOUT;
    }

    /**
     * Verifica se este é um status de execução em andamento.
     *
     * @return true se a execução estiver em andamento
     */
    public boolean isRunning() {
        return this == RUNNING || this == PAUSED;
    }
}