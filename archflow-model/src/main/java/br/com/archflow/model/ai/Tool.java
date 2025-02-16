package br.com.archflow.model.ai;

import br.com.archflow.core.ExecutionContext;
import br.com.archflow.model.ai.domain.ParameterDescription;
import br.com.archflow.model.ai.domain.Result;

import java.util.List;
import java.util.Map;

/**
 * Interface base para Ferramentas de IA.
 */
public interface Tool extends AIComponent {
    /**
     * Executa a ferramenta com os parâmetros fornecidos.
     */
    Result execute(Map<String, Object> params, ExecutionContext context);

    /**
     * Retorna a descrição dos parâmetros aceitos.
     */
    List<ParameterDescription> getParameters();

    /**
     * Valida os parâmetros antes da execução.
     */
    void validateParameters(Map<String, Object> params);
}