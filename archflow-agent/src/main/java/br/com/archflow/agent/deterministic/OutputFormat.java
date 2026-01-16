package br.com.archflow.agent.deterministic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Output format options for deterministic agent execution.
 *
 * <p>Func-Agent Mode requires structured, predictable outputs for:
 * <ul>
 *   <li><b>JSON:</b> Structured data with schema validation</li>
 *   <li><b>XML:</b> XML format for enterprise integration</li>
 *   <li><b>CSV:</b> Tabular data for reporting</li>
 *   <li><b>PLAIN:</b> Plain text output</li>
 * </ul>
 */
public enum OutputFormat {

    /**
     * JSON output - structured data format.
     */
    JSON {
        @Override
        public String format(Object data, OutputSchema schema) throws FormatException {
            try {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(data);

                if (schema != null && schema.getJsonSchema() != null) {
                    validateAgainstSchema(json, schema.getJsonSchema());
                }

                return json;
            } catch (JsonProcessingException e) {
                throw new FormatException("Failed to format as JSON", e);
            }
        }

        @Override
        public String format(Map<String, Object> data, OutputSchema schema) throws FormatException {
            return format((Object) data, schema);
        }

        @Override
        public String format(List<Map<String, Object>> data, OutputSchema schema) throws FormatException {
            return format((Object) data, schema);
        }

        private void validateAgainstSchema(String json, String jsonSchema) throws FormatException {
            // TODO: Implement JSON Schema validation
            // For now, just validate that it's valid JSON
            try {
                new ObjectMapper().readTree(json);
            } catch (JsonProcessingException e) {
                throw new FormatException("Invalid JSON output", e);
            }
        }
    },

    /**
     * XML output - for enterprise integration.
     */
    XML {
        @Override
        public String format(Object data, OutputSchema schema) throws FormatException {
            try {
                XmlMapper xmlMapper = new XmlMapper();
                return xmlMapper.writeValueAsString(data);
            } catch (Exception e) {
                throw new FormatException("Failed to format as XML", e);
            }
        }

        @Override
        public String format(Map<String, Object> data, OutputSchema schema) throws FormatException {
            return format((Object) data, schema);
        }

        @Override
        public String format(List<Map<String, Object>> data, OutputSchema schema) throws FormatException {
            try {
                XmlMapper xmlMapper = new XmlMapper();
                ObjectNode root = xmlMapper.createObjectNode();
                ArrayNode items = root.putArray("items");
                for (Map<String, Object> item : data) {
                    items.addPOJO(item);
                }
                return xmlMapper.writeValueAsString(root);
            } catch (Exception e) {
                throw new FormatException("Failed to format as XML", e);
            }
        }
    },

    /**
     * CSV output - for tabular data and reporting.
     */
    CSV {
        @Override
        public String format(Object data, OutputSchema schema) throws FormatException {
            if (!(data instanceof List)) {
                throw new FormatException("CSV format requires List data");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) data;
            return format(list, schema);
        }

        @Override
        public String format(Map<String, Object> data, OutputSchema schema) throws FormatException {
            throw new FormatException("CSV format requires List of Map data, not single Map");
        }

        @Override
        public String format(List<Map<String, Object>> data, OutputSchema schema) throws FormatException {
            try {
                CsvMapper csvMapper = new CsvMapper();
                if (data.isEmpty()) {
                    return "";
                }

                CsvSchema csvSchema = csvMapper.schemaFor(Map.class);
                String csv = csvMapper.writer(csvSchema)
                        .writeValueAsString(data);

                return csv;
            } catch (Exception e) {
                throw new FormatException("Failed to format as CSV", e);
            }
        }
    },

    /**
     * Plain text output - simple unstructured text.
     */
    PLAIN {
        @Override
        public String format(Object data, OutputSchema schema) throws FormatException {
            if (data == null) {
                return "";
            }
            return data.toString();
        }

        @Override
        public String format(Map<String, Object> data, OutputSchema schema) throws FormatException {
            return format((Object) data, schema);
        }

        @Override
        public String format(List<Map<String, Object>> data, OutputSchema schema) throws FormatException {
            return format((Object) data, schema);
        }
    };

    /**
     * Formats the given data according to this format.
     *
     * @param data The data to format
     * @param schema The optional schema for validation
     * @return The formatted string
     * @throws FormatException if formatting fails
     */
    public abstract String format(Object data, OutputSchema schema) throws FormatException;

    /**
     * Formats a map of data.
     *
     * @param data The data map
     * @param schema The optional schema
     * @return The formatted string
     * @throws FormatException if formatting fails
     */
    public abstract String format(Map<String, Object> data, OutputSchema schema) throws FormatException;

    /**
     * Formats a list of maps (tabular data).
     *
     * @param data The list of data maps
     * @param schema The optional schema
     * @return The formatted string
     * @throws FormatException if formatting fails
     */
    public abstract String format(List<Map<String, Object>> data, OutputSchema schema) throws FormatException;

    /**
     * Parses a string format from a config value.
     *
     * @param value The format name (case-insensitive)
     * @return The OutputFormat
     * @throws IllegalArgumentException if the format is unknown
     */
    public static OutputFormat fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return JSON; // default
        }
        try {
            return OutputFormat.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown output format: " + value +
                    ". Valid values: JSON, XML, CSV, PLAIN");
        }
    }

    /**
     * Exception thrown when formatting fails.
     */
    public static class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }

        public FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
