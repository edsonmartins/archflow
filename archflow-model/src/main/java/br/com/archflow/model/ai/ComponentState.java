package br.com.archflow.model.ai;

/**
 * Representa o estado atual de um componente de IA.
 */
public record ComponentState(
    StateType type,
    String message,
    long lastUpdated
) {
    public enum StateType {
        UNINITIALIZED,  // Componente ainda não inicializado
        INITIALIZING,   // Em processo de inicialização
        READY,          // Pronto para uso
        BUSY,           // Executando operação
        ERROR,          // Em estado de erro
        SHUTTING_DOWN,  // Em processo de finalização
        SHUTDOWN        // Finalizado
    }

    public static ComponentState of(StateType type) {
        return new ComponentState(type, null, System.currentTimeMillis());
    }

    public static ComponentState of(StateType type, String message) {
        return new ComponentState(type, message, System.currentTimeMillis());
    }
}