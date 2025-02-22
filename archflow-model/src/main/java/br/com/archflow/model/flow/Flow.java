package br.com.archflow.model.flow;

import br.com.archflow.model.config.FlowConfiguration;
import java.util.List;

/**
 * Contrato principal que define um fluxo de trabalho de IA no archflow.
 * Um fluxo é uma sequência de passos que podem envolver Chains, Agents ou Tools
 * do LangChain4j, organizados em uma ordem específica de execução.
 *
 * @since 1.0.0
 */
public interface Flow {
    /**
     * Retorna o identificador único do fluxo.
     * Este ID é usado para referenciar o fluxo em toda a plataforma.
     *
     * @return identificador único do fluxo
     */
    String getId();

    /**
     * Retorna os metadados associados ao fluxo.
     * Contém informações como nome, descrição, versão, etc.
     *
     * @return metadados do fluxo
     */
    FlowMetadata getMetadata();

    /**
     * Retorna a lista ordenada de passos que compõem o fluxo.
     * A ordem dos passos define a sequência de execução.
     *
     * @return lista de passos do fluxo
     */
    List<FlowStep> getSteps();

    /**
     * Retorna a configuração específica deste fluxo.
     * Inclui parâmetros como timeout, retry policy, etc.
     *
     * @return configuração do fluxo
     */
    FlowConfiguration getConfiguration();

    /**
     * Valida se o fluxo está corretamente configurado.
     * Verifica a consistência dos passos, conexões e parâmetros.
     *
     * @throws Exception se houver problemas na configuração
     */
    void validate() throws Exception;
}