package br.com.archflow.conversation.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Utilitários compartilhados pelos repositórios JDBC do módulo de conversação
 * (antes duplicados verbatim entre JdbcConversationRepository e
 * JdbcPromptRegistry).
 */
final class JdbcSupport {

    private static final Logger log = LoggerFactory.getLogger(JdbcSupport.class);

    /** ObjectMapper único e thread-safe — evita recriar/escanear módulos por instância. */
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JdbcSupport() {
    }

    /** Liga os parâmetros de um {@link PreparedStatement} a partir de uma query. */
    @FunctionalInterface
    interface ParameterBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** Serializa um mapa de metadados como JSON; nulo vira objeto vazio. */
    static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata; storing empty object", e);
            return "{}";
        }
    }

    /** Desserializa JSON em mapa de metadados; nulo/erro vira objeto vazio. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize metadata; returning empty object", e);
            return Map.of();
        }
    }
}
