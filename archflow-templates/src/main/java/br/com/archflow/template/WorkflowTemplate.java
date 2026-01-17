package br.com.archflow.template;

import br.com.archflow.model.Workflow;

import java.util.Map;
import java.util.Set;

/**
 * Interface for workflow templates.
 *
 * <p>Workflow templates provide pre-built workflows for common AI patterns
 * that can be instantiated and customized.
 *
 * @see WorkflowTemplateRegistry
 */
public interface WorkflowTemplate {

    /**
     * Gets the unique identifier for this template.
     *
     * @return The template ID (e.g., "customer-support", "document-processing")
     */
    String getId();

    /**
     * Gets the display name for this template.
     *
     * @return A human-readable name
     */
    String getDisplayName();

    /**
     * Gets the description of this template.
     *
     * @return A description of what the template does
     */
    String getDescription();

    /**
     * Gets the category this template belongs to.
     *
     * @return The category (e.g., "support", "processing", "automation")
     */
    String getCategory();

    /**
     * Gets the tags associated with this template.
     *
     * @return Set of tags for discovery
     */
    Set<String> getTags();

    /**
     * Gets the default workflow definition.
     *
     * @return The workflow template definition
     */
    WorkflowTemplateDefinition getDefinition();

    /**
     * Gets the configurable parameters for this template.
     *
     * @return Map of parameter names to their descriptions
     */
    Map<String, ParameterDefinition> getParameters();

    /**
     * Creates a new workflow instance from this template.
     *
     * @param name The name for the new workflow
     * @param parameters The parameters to configure the workflow
     * @return A configured workflow ready for execution
     */
    Workflow createInstance(String name, Map<String, Object> parameters);

    /**
     * Validates parameters for this template.
     *
     * @param parameters The parameters to validate
     * @throws IllegalArgumentException if any parameter is invalid
     */
    default void validateParameters(Map<String, Object> parameters) {
        for (Map.Entry<String, ParameterDefinition> entry : getParameters().entrySet()) {
            String key = entry.getKey();
            ParameterDefinition def = entry.getValue();

            if (def.required() && (parameters == null || !parameters.containsKey(key))) {
                throw new IllegalArgumentException(
                        "Required parameter '" + key + "' is missing for template '" + getId() + "'");
            }

            if (parameters != null && parameters.containsKey(key)) {
                Object value = parameters.get(key);
                if (value != null && !def.type().isInstance(value)) {
                    throw new IllegalArgumentException(
                            "Parameter '" + key + "' must be of type " + def.type().getSimpleName());
                }
            }
        }
    }

    /**
     * Definition of a template parameter.
     */
    record ParameterDefinition(
            String name,
            String description,
            Class<?> type,
            Object defaultValue,
            boolean required,
            String[] options
    ) {
        public static ParameterDefinition required(String name, String description, Class<?> type) {
            return new ParameterDefinition(name, description, type, null, true, null);
        }

        public static ParameterDefinition optional(String name, String description, Class<?> type, Object defaultValue) {
            return new ParameterDefinition(name, description, type, defaultValue, false, null);
        }

        public static ParameterDefinition enumParameter(String name, String description, String... options) {
            return new ParameterDefinition(name, description, String.class, options[0], true, options);
        }
    }
}
