package br.com.archflow.langchain4j.core.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Serializes/deserializes LangChain4j {@link Metadata} as a plain JSON object
 * ({@code {"key": value, ...}}), preserving value types (string vs. number)
 * across the round-trip.
 *
 * <p>Used by vector store adapters that persist metadata as a single JSON
 * text value (portable: works with any backend that can store a string).
 *
 * <p>On deserialization, values are coerced to the types supported by
 * {@link Metadata} (String, UUID, Integer, Long, Float, Double): booleans and
 * other unsupported scalars are coerced to their string form, big integers
 * that do not fit a {@code long} and arbitrary structures fall back to
 * strings, and {@code null} values are skipped.
 */
public final class MetadataJsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private MetadataJsonCodec() {
    }

    /**
     * Serializes metadata to a JSON object string.
     *
     * @param metadata the metadata to serialize
     * @return the JSON string, or {@code null} if the metadata is {@code null} or empty
     */
    public static String toJson(Metadata metadata) {
        if (metadata == null || metadata.toMap().isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : metadata.toMap().entrySet()) {
                Object value = entry.getValue();
                // UUID is not a native JSON type — persist as string
                map.put(entry.getKey(), value instanceof UUID ? value.toString() : value);
            }
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize metadata to JSON", e);
        }
    }

    /**
     * Deserializes a JSON object string back into {@link Metadata}.
     *
     * @param json the JSON string; {@code null} or blank yields empty metadata
     * @return the metadata (never {@code null})
     * @throws IllegalArgumentException if the string is not a valid JSON object
     */
    public static Metadata fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new Metadata();
        }
        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid metadata JSON: " + json, e);
        }
        return toMetadata(raw);
    }

    /**
     * Builds {@link Metadata} from a generic map (e.g. parsed JSON), coercing
     * values that {@link Metadata} does not support into supported types.
     * {@code null} values are skipped.
     *
     * @param raw the raw map; {@code null} yields empty metadata
     * @return the metadata (never {@code null})
     */
    public static Metadata toMetadata(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return new Metadata();
        }
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Object value = coerce(entry.getValue());
            if (value != null) {
                coerced.put(entry.getKey(), value);
            }
        }
        return new Metadata(coerced);
    }

    private static Object coerce(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof UUID
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return bigInteger.longValueExact();
            } catch (ArithmeticException e) {
                return bigInteger.toString();
            }
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.doubleValue();
        }
        if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        // Booleans, lists, nested maps, etc. — Metadata does not support them
        return String.valueOf(value);
    }
}
