package br.com.archflow.plugins.tools;

import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.domain.ParameterDescription;
import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference tool plugin that performs text transformations.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code uppercase} - Converts text to upper case</li>
 *   <li>{@code lowercase} - Converts text to lower case</li>
 *   <li>{@code reverse} - Reverses the text</li>
 *   <li>{@code wordcount} - Counts words in the text</li>
 * </ul>
 */
public class TextTransformTool implements Tool, ComponentPlugin {

    private static final String COMPONENT_ID = "text-transform-tool";
    private static final String VERSION = "1.0.0";

    private Map<String, Object> config;
    private boolean initialized = false;

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config;
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "Text Transform Tool",
                "Performs text transformations: uppercase, lowercase, reverse, word count",
                ComponentType.TOOL,
                VERSION,
                Set.of("text-processing", "transform"),
                List.of(
                        new ComponentMetadata.OperationMetadata(
                                "uppercase", "Uppercase", "Convert text to upper case",
                                List.of(new ComponentMetadata.ParameterMetadata("text", "string", "Input text", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "string", "Transformed text", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "lowercase", "Lowercase", "Convert text to lower case",
                                List.of(new ComponentMetadata.ParameterMetadata("text", "string", "Input text", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "string", "Transformed text", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "reverse", "Reverse", "Reverse the text",
                                List.of(new ComponentMetadata.ParameterMetadata("text", "string", "Input text", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "string", "Reversed text", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "wordcount", "Word Count", "Count words in text",
                                List.of(new ComponentMetadata.ParameterMetadata("text", "string", "Input text", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "integer", "Word count", true))
                        )
                ),
                Map.of(),
                Set.of("text", "utility", "reference")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Tool not initialized. Call initialize() first.");
        }

        String text = (input instanceof String) ? (String) input : String.valueOf(input);

        return switch (operation) {
            case "uppercase" -> text.toUpperCase();
            case "lowercase" -> text.toLowerCase();
            case "reverse" -> new StringBuilder(text).reverse().toString();
            case "wordcount" -> countWords(text);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    @Override
    public Result execute(Map<String, Object> params, ExecutionContext context) {
        String operation = (String) params.get("operation");
        String text = (String) params.get("text");

        if (operation == null || operation.isBlank()) {
            return Result.failure("Parameter 'operation' is required");
        }
        if (text == null) {
            return Result.failure("Parameter 'text' is required");
        }

        try {
            Object result = execute(operation, text, context);
            return Result.success(result);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @Override
    public List<ParameterDescription> getParameters() {
        return List.of(
                new ParameterDescription("operation", "string",
                        "Operation to perform: uppercase, lowercase, reverse, wordcount",
                        true, null, List.of("uppercase", "lowercase", "reverse", "wordcount")),
                new ParameterDescription("text", "string",
                        "Input text to transform",
                        true, null, List.of())
        );
    }

    @Override
    public void validateParameters(Map<String, Object> params) {
        if (params == null || !params.containsKey("text")) {
            throw new IllegalArgumentException("Parameter 'text' is required");
        }
        String operation = (String) params.get("operation");
        if (operation == null || !Set.of("uppercase", "lowercase", "reverse", "wordcount").contains(operation)) {
            throw new IllegalArgumentException(
                    "Parameter 'operation' must be one of: uppercase, lowercase, reverse, wordcount");
        }
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        // No required configuration for this tool
    }

    @Override
    public void shutdown() {
        this.config = null;
        this.initialized = false;
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
