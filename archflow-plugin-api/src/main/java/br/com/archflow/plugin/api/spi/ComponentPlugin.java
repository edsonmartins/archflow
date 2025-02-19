package br.com.archflow.plugin.api.spi;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.ExecutionContext;

import java.util.Map;

/**
 * Interface que define o contrato para componentes implementados como plugins.
 */
public interface ComponentPlugin extends AIComponent {
    /**
     * Valida a configuração do plugin.
     * @param config configuração a ser validada
     * @throws br.com.archflow.plugin.api.exception.ComponentException se configuração inválida
     */
    void validateConfig(Map<String, Object> config);
    
    /**
     * Chamado quando o plugin é carregado.
     * @param context contexto de execução
     */
    default void onLoad(ExecutionContext context) {}
    
    /**
     * Chamado quando o plugin é descarregado.
     */
    default void onUnload() {}
}