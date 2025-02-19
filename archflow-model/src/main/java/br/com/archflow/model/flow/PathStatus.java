package br.com.archflow.model.flow;

public enum PathStatus {
    /**
     * Caminho de execução iniciado
     */
    STARTED,
    
    /**
     * Caminho em execução
     */
    RUNNING,
    
    /**
     * Caminho pausado (aguardando ação)
     */
    PAUSED,
    
    /**
     * Caminho concluído com sucesso
     */
    COMPLETED,
    
    /**
     * Caminho falhou durante execução
     */
    FAILED,
    
    /**
     * Caminho foi mesclado após execução paralela
     */
    MERGED;
    
    public boolean isActive() {
        return this == STARTED || this == RUNNING;
    }
    
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == MERGED;
    }
}