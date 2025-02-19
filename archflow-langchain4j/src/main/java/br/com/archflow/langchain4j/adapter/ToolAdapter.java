package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * Adaptador base para ferramentas do LangChain4j.
 * Converte ferramentas do archflow para o formato do LangChain4j.
 */
public abstract class ToolAdapter implements LangChainAdapter {
    protected ToolSpecification toolSpec;

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.toolSpec = ToolSpecification.builder()
                .name(getToolName())
                .description(getToolDescription())
                .parameters(createParametersSchema())
                .build();
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!"execute".equals(operation)) {
            throw new IllegalArgumentException("Invalid operation for tool: " + operation);
        }

        Map<String, Object> parameters = (Map<String, Object>) input;
        return executeTool(parameters, context);
    }

    @Override
    public void validate(Map<String, Object> properties) {
        // Validação padrão de propriedades
        if (getToolName() == null || getToolName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name is required");
        }
    }

    @Override
    public void shutdown() {
        // Implementação padrão - pode ser sobrescrita se necessário
    }

    /**
     * Obtém a especificação da ferramenta para uso com LangChain4j
     */
    public ToolSpecification getToolSpecification() {
        if (toolSpec == null) {
            throw new IllegalStateException("Tool not configured. Call configure() first.");
        }
        return toolSpec;
    }

    /**
     * Nome da ferramenta
     */
    protected abstract String getToolName();

    /**
     * Descrição da ferramenta
     */
    protected abstract String getToolDescription();

    /**
     * Schema de parâmetros da ferramenta
     */
    protected abstract JsonObjectSchema createParametersSchema();

    /**
     * Execução específica da ferramenta
     */
    protected abstract Object executeTool(Map<String, Object> parameters, ExecutionContext context) throws Exception;
}