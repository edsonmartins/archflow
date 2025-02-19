package br.com.archflow.engine.validation;

import br.com.archflow.model.flow.Flow;

import java.util.HashMap;
import java.util.Map;

/**
 * Contexto usado durante a validação.
 * Mantém informações relevantes para validação de passos e conexões.
 */
public class ValidationContext {
    private final Flow flow;
    private final Map<String, Object> attributes = new HashMap<>();

    public ValidationContext(Flow flow) {
        this.flow = flow;
    }

    public Flow getFlow() {
        return flow;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}