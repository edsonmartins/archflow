package br.com.archflow.plugin.api.spi;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.ExecutionContext;

import java.util.Map;

/**
 * Interface base para plugins do archflow.
 * Define o contrato básico que todos os plugins devem implementar.
 */
public interface ComponentPlugin {
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