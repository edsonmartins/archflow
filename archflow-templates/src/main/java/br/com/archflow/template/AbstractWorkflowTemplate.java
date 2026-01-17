package br.com.archflow.template;

import br.com.archflow.model.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Abstract base class for workflow templates.
 *
 * <p>Provides common functionality for template implementations,
 * including parameter handling and workflow instantiation.
 */
public abstract class AbstractWorkflowTemplate implements WorkflowTemplate {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorkflowTemplate.class);

    private final String id;
    private final String displayName;
    private final String description;
    private final String category;
    private final Set<String> tags;
    private final Map<String, ParameterDefinition> parameters;

    protected AbstractWorkflowTemplate(
            String id,
            String displayName,
            String description,
            String category,
            Set<String> tags,
            Map<String, ParameterDefinition> parameters) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.category = category;
        this.tags = tags != null ? Set.copyOf(tags) : Set.of();
        this.parameters = Map.copyOf(parameters);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public Set<String> getTags() {
        return tags;
    }

    @Override
    public Map<String, ParameterDefinition> getParameters() {
        return parameters;
    }

    @Override
    public Workflow createInstance(String name, Map<String, Object> parameters) {
        validateParameters(parameters);

        // Merge defaults with provided parameters
        Map<String, Object> resolvedParameters = resolveParameters(parameters);

        log.info("Creating workflow '{}' from template '{}'", name, id);

        return buildWorkflow(name, resolvedParameters);
    }

    /**
     * Builds the workflow from the resolved parameters.
     *
     * @param name The name for the workflow
     * @param parameters The resolved parameters
     * @return The constructed workflow
     */
    protected abstract Workflow buildWorkflow(String name, Map<String, Object> parameters);

    /**
     * Resolves parameters by merging defaults with provided values.
     *
     * @param parameters The user-provided parameters
     * @return Resolved parameters with defaults applied
     */
    protected Map<String, Object> resolveParameters(Map<String, Object> parameters) {
        Map<String, Object> resolved = new HashMap<>();

        // Apply defaults
        for (Map.Entry<String, ParameterDefinition> entry : this.parameters.entrySet()) {
            String key = entry.getKey();
            ParameterDefinition def = entry.getValue();

            Object value = (parameters != null && parameters.containsKey(key))
                    ? parameters.get(key)
                    : def.defaultValue();

            resolved.put(key, value);
        }

        // Add any extra parameters from user
        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (!resolved.containsKey(entry.getKey())) {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return resolved;
    }

    /**
     * Gets a string parameter value.
     *
     * @param parameters The parameters map
     * @param key The parameter key
     * @param defaultValue The default value if not found
     * @return The parameter value
     */
    protected String getString(Map<String, Object> parameters, String key, String defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * Gets an integer parameter value.
     *
     * @param parameters The parameters map
     * @param key The parameter key
     * @param defaultValue The default value if not found
     * @return The parameter value
     */
    protected int getInt(Map<String, Object> parameters, String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Gets a boolean parameter value.
     *
     * @param parameters The parameters map
     * @param key The parameter key
     * @param defaultValue The default value if not found
     * @return The parameter value
     */
    protected boolean getBoolean(Map<String, Object> parameters, String key, boolean defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
}
