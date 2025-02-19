package br.com.archflow.model.flow;

public enum FlowStatus {
    /**
     * Fluxo iniciado mas ainda não em execução
     */
    INITIALIZED,
    
    /**
     * Fluxo em execução
     */
    RUNNING,
    
    /**
     * Fluxo pausado
     */
    PAUSED,
    
    /**
     * Fluxo concluído com sucesso
     */
    COMPLETED,
    
    /**
     * Fluxo falhou durante execução
     */
    FAILED,
    
    /**
     * Fluxo cancelado/parado manualmente
     */
    STOPPED;
    
    /**
     * Verifica se o status é final (não permite mais execução)
     */
    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == STOPPED;
    }
    
    /**
     * Verifica se o status permite continuar a execução
     */
    public boolean canContinue() {
        return this == INITIALIZED || this == RUNNING || this == PAUSED;
    }
}