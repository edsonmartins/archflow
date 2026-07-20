package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converte as {@link McpModel.Tool tools} descobertas de um MCP server (JSON
 * Schema no {@code inputSchema}) em {@link ToolSpecification} do LangChain4j,
 * com os tipos REAIS de cada propriedade (string/integer/number/boolean/array/
 * object/enum) — ao contrário do caminho AG-UI, que trata tudo como string.
 */
public final class McpToolSpecifications {

    private McpToolSpecifications() {
    }

    public static List<ToolSpecification> from(List<McpModel.Tool> tools) {
        List<ToolSpecification> specs = new ArrayList<>(tools.size());
        for (McpModel.Tool tool : tools) {
            specs.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(toObjectSchema(tool.inputSchema()))
                    .build());
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private static JsonObjectSchema toObjectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        Object description = schema.get("description");
        if (description instanceof String d && !d.isBlank()) {
            builder.description(d);
        }
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> e : properties.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (e.getValue() instanceof Map<?, ?> propSchema) {
                    builder.addProperty(name, toElement((Map<String, Object>) propSchema));
                }
            }
        }
        Object required = schema.get("required");
        if (required instanceof List<?> reqList && !reqList.isEmpty()) {
            List<String> names = new ArrayList<>();
            reqList.forEach(r -> names.add(String.valueOf(r)));
            builder.required(names);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static JsonSchemaElement toElement(Map<String, Object> schema) {
        String type = schema.get("type") instanceof String t ? t : "string";
        String description = schema.get("description") instanceof String d ? d : null;

        if (schema.get("enum") instanceof List<?> enumValues && !enumValues.isEmpty()) {
            List<String> values = new ArrayList<>();
            enumValues.forEach(v -> values.add(String.valueOf(v)));
            JsonEnumSchema.Builder b = JsonEnumSchema.builder().enumValues(values);
            if (description != null) b.description(description);
            return b.build();
        }

        return switch (type) {
            case "integer" -> {
                JsonIntegerSchema.Builder b = JsonIntegerSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "number" -> {
                JsonNumberSchema.Builder b = JsonNumberSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "boolean" -> {
                JsonBooleanSchema.Builder b = JsonBooleanSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "array" -> {
                JsonArraySchema.Builder b = JsonArraySchema.builder();
                if (description != null) b.description(description);
                Object items = schema.get("items");
                b.items(items instanceof Map<?, ?> itemSchema
                        ? toElement((Map<String, Object>) itemSchema)
                        : JsonStringSchema.builder().build());
                yield b.build();
            }
            case "object" -> toObjectSchema(schema);
            default -> {
                JsonStringSchema.Builder b = JsonStringSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
        };
    }
}
