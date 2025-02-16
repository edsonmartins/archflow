package br.com.archflow.plugin.api.spi;


import br.com.archflow.plugin.api.metadata.PluginDescriptor;

import java.util.List;
import java.util.Map;

/**
 * Interface principal que todos os plugins do archflow devem implementar.
 * Define o contrato base para plugins que fornecem funcionalidades de IA.
 */
public interface AIPlugin {
    
    /**
     * Inicializa o plugin com a configuração fornecida.
     *
     * @param configuration configuração do plugin
     * @throws Exception se houver erro na inicialização
     */
    void initialize(Map<String, Object> configuration) throws Exception;

    /**
     * Retorna as capacidades fornecidas por este plugin.
     * Capacidades são funcionalidades específicas que o plugin pode executar.
     *
     * @return lista de capacidades
     */
    List<String> getCapabilities();

    /**
     * Executa uma operação específica do plugin.
     *
     * @param operationId identificador da operação
     * @param input entrada para a operação
     * @param context contexto de execução
     * @return resultado da operação
     * @throws Exception se houver erro na execução
     */
    Object execute(String operationId, Object input, Map<String, Object> context) throws Exception;

    /**
     * Finaliza o plugin, liberando recursos.
     *
     * @throws Exception se houver erro no shutdown
     */
    void shutdown() throws Exception;

    /**
     * Retorna os metadados do plugin.
     * Por padrão, obtém os metadados da anotação PluginDescriptor.
     *
     * @return metadados do plugin
     */
    default PluginDescriptor getMetadata() {
        return getClass().getAnnotation(PluginDescriptor.class);
    }
}