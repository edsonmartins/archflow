package br.com.archflow.workflow.tool.langchain4j;

import br.com.archflow.workflow.tool.WorkflowTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static factory that converts {@link WorkflowTool} metadata into JSON Schema
 * representations compatible with LangChain4j's tool specification format.
 *
 * <p>Since archflow-workflow-tool does not depend on LangChain4j directly, this factory
 * produces standard JSON Schema as {@code Map<String, Object>} structures. These can
 * be consumed by a downstream LangChain4j integration module to build native
 * {@code ToolSpecification} objects.</p>
 *
 * <p>The factory maps WorkflowTool input schema parameter types to JSON Schema types:
 * <ul>
 *   <li>{@code "string"} -&gt; {@code {"type": "string"}}</li>
 *   <li>{@code "integer"} -&gt; {@code {"type": "integer"}}</li>
 *   <li>{@code "number"} -&gt; {@code {"type": "number"}}</li>
 *   <li>{@code "boolean"} -&gt; {@code {"type": "boolean"}}</li>
 *   <li>{@code "array"} -&gt; {@code {"type": "array"}}</li>
 *   <li>{@code "object"} -&gt; {@code {"type": "object"}}</li>
 * </ul>
 *
 * @see WorkflowTool
 * @see LangChain4jToolAdapter
 */
public final class WorkflowToolSpecificationFactory {

    private static final Logger log = LoggerFactory.getLogger(WorkflowToolSpecificationFactory.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "string", "integer", "number", "boolean", "array", "object"
    );

    private WorkflowToolSpecificationFactory() {
        // Utility class - no instantiation
    }

    /**
     * Creates a JSON Schema representation of a WorkflowTool's input parameters.
     *
     * <p>The returned Map follows the JSON Schema specification with:
     * <ul>
     *   <li>{@code "type"}: always "object"</li>
     *   <li>{@code "properties"}: parameter definitions</li>
     *   <li>{@code "required"}: list of required parameter names (parameters marked as required)</li>
     * </ul>
     *
     * @param tool the workflow tool to create the schema for
     * @return a Map representing the JSON Schema
     * @throws NullPointerException if tool is null
     */
    public static Map<String, Object> createJsonSchema(WorkflowTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");

        Map<String, Object> inputSchema = tool.getInputSchema();
        if (inputSchema == null || inputSchema.isEmpty()) {
            return createEmptySchema();
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();

        for (Map.Entry<String, Object> entry : inputSchema.entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            Map<String, Object> propertySchema = mapParameter(paramName, paramValue);
            properties.put(paramName, propertySchema);

            // If the parameter value is a Map with "required" flag, track it
            if (isRequired(paramValue)) {
                required.add(paramName);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Creates a complete tool specification Map containing name, description, and input schema.
     *
     * @param tool the workflow tool
     * @return a Map with "name", "description", and "inputSchema" keys
     * @throws NullPointerException if tool is null
     */
    public static Map<String, Object> createSpecification(WorkflowTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", tool.getName());
        if (tool.getDescription() != null) {
            spec.put("description", tool.getDescription());
        }
        spec.put("inputSchema", createJsonSchema(tool));

        return spec;
    }

    /**
     * Maps a single parameter entry to a JSON Schema property definition.
     *
     * @param paramName the parameter name
     * @param paramValue the parameter value (type string, or Map with type and other metadata)
     * @return the JSON Schema property definition
     */
    static Map<String, Object> mapParameter(String paramName, Object paramValue) {
        if (paramValue instanceof String typeStr) {
            return mapSimpleType(typeStr);
        }

        if (paramValue instanceof Map<?, ?> paramMap) {
            return mapComplexParameter(paramName, paramMap);
        }

        log.warn("Unsupported parameter definition for '{}': {}. Defaulting to string type.",
                paramName, paramValue);
        return mapSimpleType("string");
    }

    private static Map<String, Object> mapSimpleType(String typeStr) {
        String normalizedType = normalizeType(typeStr);
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", normalizedType);
        return property;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapComplexParameter(String paramName, Map<?, ?> paramMap) {
        Map<String, Object> property = new LinkedHashMap<>();

        Object type = paramMap.get("type");
        if (type instanceof String typeStr) {
            property.put("type", normalizeType(typeStr));
        } else {
            property.put("type", "string");
        }

        Object description = paramMap.get("description");
        if (description instanceof String descStr) {
            property.put("description", descStr);
        }

        Object enumValues = paramMap.get("enum");
        if (enumValues instanceof List<?> enumList) {
            property.put("enum", enumList);
        }

        Object defaultValue = paramMap.get("default");
        if (defaultValue != null) {
            property.put("default", defaultValue);
        }

        // Handle array items
        if ("array".equals(property.get("type"))) {
            Object items = paramMap.get("items");
            if (items instanceof Map<?, ?> itemsMap) {
                property.put("items", new LinkedHashMap<>(itemsMap));
            } else if (items instanceof String itemType) {
                property.put("items", Map.of("type", normalizeType(itemType)));
            }
        }

        // Handle object properties
        if ("object".equals(property.get("type"))) {
            Object nestedProperties = paramMap.get("properties");
            if (nestedProperties instanceof Map<?, ?> nestedMap) {
                Map<String, Object> mappedNested = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nestedEntry : nestedMap.entrySet()) {
                    String nestedName = String.valueOf(nestedEntry.getKey());
                    mappedNested.put(nestedName, mapParameter(nestedName, nestedEntry.getValue()));
                }
                property.put("properties", mappedNested);
            }
        }

        return property;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "string";
        }
        String lower = type.toLowerCase().trim();
        if (SUPPORTED_TYPES.contains(lower)) {
            return lower;
        }
        // Map common aliases
        return switch (lower) {
            case "int", "long" -> "integer";
            case "float", "double", "decimal" -> "number";
            case "bool" -> "boolean";
            case "str", "text" -> "string";
            case "list" -> "array";
            case "map", "dict" -> "object";
            default -> {
                log.warn("Unknown parameter type '{}', defaulting to 'string'", type);
                yield "string";
            }
        };
    }

    private static boolean isRequired(Object paramValue) {
        if (paramValue instanceof Map<?, ?> paramMap) {
            Object required = paramMap.get("required");
            if (required instanceof Boolean boolRequired) {
                return boolRequired;
            }
        }
        return false;
    }

    private static Map<String, Object> createEmptySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
}
