package br.com.archflow.model.ai;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.engine.ExecutionContext;

import java.util.Map;

/**
 * Interface base para todos os componentes IA no archflow.
 */
public interface AIComponent {
    void initialize(Map<String, Object> config);
    ComponentMetadata getMetadata();
    Object execute(String operation, Object input, ExecutionContext context) throws Exception;
    void shutdown();
}