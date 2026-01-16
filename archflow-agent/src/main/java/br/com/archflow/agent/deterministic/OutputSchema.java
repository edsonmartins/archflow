package br.com.archflow.agent.deterministic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema definition for deterministic agent output validation.
 *
 * <p>Defines the expected structure of agent output:
 * <ul>
 *   <li><b>Required fields:</b> Fields that must be present</li>
 *   <li><b>Field types:</b> Type constraints (string, number, boolean, array, object)</li>
 *   <li><b>Value constraints:</b> Enum values, ranges, patterns</li>
 *   <li><b>Nested structures:</b> Support for nested objects and arrays</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * OutputSchema schema = OutputSchema.builder()
 *     .addRequiredField("customer_id", FieldType.STRING)
 *     .addRequiredField("total", FieldType.NUMBER)
 *     .addRequiredField("items", FieldType.ARRAY)
 *     .addFieldConstraint("status", EnumConstraint.of("pending", "approved", "rejected"))
 *     .build();
 *
 * schema.validate(outputData);
 * </pre>
 */
public class OutputSchema {

    private static final Logger log = LoggerFactory.getLogger(OutputSchema.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String schemaName;
    private final String jsonSchema;
    private final Map<String, FieldDefinition> fields;
    private final boolean strictMode;

    private OutputSchema(Builder builder) {
        this.schemaName = builder.schemaName;
        this.jsonSchema = builder.jsonSchema;
        this.fields = Map.copyOf(builder.fields);
        this.strictMode = builder.strictMode;
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates the given data against this schema.
     *
     * @param data The data to validate (as Map or String)
     * @throws ValidationException if validation fails
     */
    public void validate(Object data) throws ValidationException {
        if (data == null) {
            throw new ValidationException("Data is null");
        }

        Map<String, Object> dataMap = asMap(data);

        List<String> errors = new ArrayList<>();

        // Check required fields
        for (Map.Entry<String, FieldDefinition> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            FieldDefinition fieldDef = entry.getValue();

            if (fieldDef.required && !dataMap.containsKey(fieldName)) {
                errors.add("Required field missing: " + fieldName);
                continue;
            }

            if (dataMap.containsKey(fieldName)) {
                Object value = dataMap.get(fieldName);
                validateField(fieldName, value, fieldDef, errors);
            }
        }

        // In strict mode, no extra fields allowed
        if (strictMode) {
            for (String key : dataMap.keySet()) {
                if (!fields.containsKey(key)) {
                    errors.add("Unexpected field in strict mode: " + key);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Schema validation failed: " + String.join("; ", errors));
        }

        log.debug("Schema validation passed for: {}", schemaName);
    }

    /**
     * Validates JSON string against this schema.
     *
     * @param json The JSON string to validate
     * @throws ValidationException if validation fails
     */
    public void validateJson(String json) throws ValidationException {
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> dataMap = objectMapper.convertValue(node, Map.class);
            validate(dataMap);
        } catch (Exception e) {
            throw new ValidationException("Failed to parse or validate JSON", e);
        }
    }

    private Map<String, Object> asMap(Object data) throws ValidationException {
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) data;
            return map;
        }
        if (data instanceof String) {
            try {
                return objectMapper.readValue((String) data, Map.class);
            } catch (Exception e) {
                throw new ValidationException("Cannot convert String to Map", e);
            }
        }
        throw new ValidationException("Unsupported data type: " + data.getClass());
    }

    private void validateField(String fieldName, Object value, FieldDefinition fieldDef, List<String> errors) {
        if (value == null) {
            if (fieldDef.required) {
                errors.add(fieldName + ": cannot be null");
            }
            return;
        }

        // Type validation
        if (!matchesType(value, fieldDef.type)) {
            errors.add(fieldName + ": expected type " + fieldDef.type + ", got " + value.getClass().getSimpleName());
            return;
        }

        // Constraint validation
        if (fieldDef.constraint != null) {
            fieldDef.constraint.validate(fieldName, value, errors);
        }

        // Nested object validation
        if (fieldDef.nestedSchema != null && value instanceof Map) {
            try {
                fieldDef.nestedSchema.validate(value);
            } catch (ValidationException e) {
                errors.add(fieldName + "." + e.getMessage());
            }
        }
    }

    private boolean matchesType(Object value, FieldType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case ARRAY -> value instanceof List;
            case OBJECT -> value instanceof Map;
            case ANY -> true;
        };
    }

    /**
     * Gets the schema name.
     */
    public String getSchemaName() {
        return schemaName;
    }

    /**
     * Gets the JSON schema string.
     */
    public String getJsonSchema() {
        return jsonSchema;
    }

    /**
     * Gets whether strict mode is enabled.
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Gets the field definitions.
     */
    public Map<String, FieldDefinition> getFields() {
        return fields;
    }

    /**
     * Builder for OutputSchema.
     */
    public static class Builder {
        private String schemaName = "default";
        private String jsonSchema;
        private final Map<String, FieldDefinition> fields = new HashMap<>();
        private boolean strictMode = false;

        private Builder() {
        }

        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public Builder jsonSchema(String jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        public Builder strictMode(boolean strictMode) {
            this.strictMode = strictMode;
            return this;
        }

        public Builder addRequiredField(String name, FieldType type) {
            fields.put(name, new FieldDefinition(name, type, true));
            return this;
        }

        public Builder addOptionalField(String name, FieldType type) {
            fields.put(name, new FieldDefinition(name, type, false));
            return this;
        }

        public Builder addField(String name, FieldType type, boolean required) {
            fields.put(name, new FieldDefinition(name, type, required));
            return this;
        }

        public Builder addFieldConstraint(String name, FieldConstraint constraint) {
            FieldDefinition fieldDef = fields.get(name);
            if (fieldDef == null) {
                fieldDef = new FieldDefinition(name, FieldType.ANY, false);
                fields.put(name, fieldDef);
            }
            fieldDef.constraint = constraint;
            return this;
        }

        public Builder addNestedSchema(String name, OutputSchema nestedSchema) {
            FieldDefinition fieldDef = fields.get(name);
            if (fieldDef == null) {
                fieldDef = new FieldDefinition(name, FieldType.OBJECT, false);
                fields.put(name, fieldDef);
            }
            fieldDef.nestedSchema = nestedSchema;
            return this;
        }

        public OutputSchema build() {
            return new OutputSchema(this);
        }
    }

    /**
     * Field definition for schema.
     */
    public static class FieldDefinition {
        private final String name;
        private final FieldType type;
        private final boolean required;
        private FieldConstraint constraint;
        private OutputSchema nestedSchema;

        private FieldDefinition(String name, FieldType type, boolean required) {
            this.name = name;
            this.type = type;
            this.required = required;
        }

        public String getName() {
            return name;
        }

        public FieldType getType() {
            return type;
        }

        public boolean isRequired() {
            return required;
        }

        public FieldConstraint getConstraint() {
            return constraint;
        }

        public OutputSchema getNestedSchema() {
            return nestedSchema;
        }
    }

    /**
     * Field type enum.
     */
    public enum FieldType {
        STRING,
        NUMBER,
        BOOLEAN,
        ARRAY,
        OBJECT,
        ANY
    }

    /**
     * Interface for field constraints.
     */
    public interface FieldConstraint {
        void validate(String fieldName, Object value, List<String> errors);

        /**
         * Creates an enum constraint (value must be one of the allowed values).
         */
        static FieldConstraint enumConstraint(Object... allowedValues) {
            return new EnumConstraint(allowedValues);
        }

        /**
         * Creates a regex pattern constraint for strings.
         */
        static FieldConstraint patternConstraint(String regex) {
            return new PatternConstraint(regex);
        }

        /**
         * Creates a range constraint for numbers.
         */
        static FieldConstraint rangeConstraint(Number min, Number max) {
            return new RangeConstraint(min, max);
        }
    }

    /**
     * Enum constraint - value must be one of the allowed values.
     */
    public static class EnumConstraint implements FieldConstraint {
        private final Object[] allowedValues;

        private EnumConstraint(Object... allowedValues) {
            this.allowedValues = allowedValues;
        }

        public static EnumConstraint of(Object... values) {
            return new EnumConstraint(values);
        }

        @Override
        public void validate(String fieldName, Object value, List<String> errors) {
            for (Object allowed : allowedValues) {
                if (allowed != null && allowed.equals(value)) {
                    return;
                }
            }
            errors.add(fieldName + ": value '" + value + "' not in allowed values: " +
                    java.util.Arrays.toString(allowedValues));
        }
    }

    /**
     * Pattern constraint - string must match regex.
     */
    public static class PatternConstraint implements FieldConstraint {
        private final java.util.regex.Pattern pattern;

        private PatternConstraint(String regex) {
            this.pattern = java.util.regex.Pattern.compile(regex);
        }

        @Override
        public void validate(String fieldName, Object value, List<String> errors) {
            if (!(value instanceof String)) {
                errors.add(fieldName + ": pattern constraint requires string value");
                return;
            }
            if (!pattern.matcher((String) value).matches()) {
                errors.add(fieldName + ": value '" + value + "' does not match pattern: " + pattern);
            }
        }
    }

    /**
     * Range constraint - number must be within range.
     */
    public static class RangeConstraint implements FieldConstraint {
        private final Number min;
        private final Number max;

        private RangeConstraint(Number min, Number max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public void validate(String fieldName, Object value, List<String> errors) {
            if (!(value instanceof Number)) {
                errors.add(fieldName + ": range constraint requires numeric value");
                return;
            }
            double num = ((Number) value).doubleValue();
            if (min != null && num < min.doubleValue()) {
                errors.add(fieldName + ": value " + num + " is less than minimum " + min);
            }
            if (max != null && num > max.doubleValue()) {
                errors.add(fieldName + ": value " + num + " is greater than maximum " + max);
            }
        }
    }

    /**
     * Exception thrown when validation fails.
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
