package br.com.archflow.model.ai;

import br.com.archflow.core.ExecutionContext;
import br.com.archflow.model.ai.domain.Analysis;
import br.com.archflow.model.ai.domain.Response;

/**
 * Interface base para Assistentes de IA.
 */
public interface AIAssistant extends AIComponent {
    /**
     * Analisa uma requisição e gera uma resposta apropriada.
     */
    Analysis analyzeRequest(String input, ExecutionContext context);

    /**
     * Gera uma resposta baseada na análise.
     */
    Response generateResponse(Analysis analysis, ExecutionContext context);
    
    /**
     * Retorna a especialização do assistente.
     */
    String getSpecialization();
}