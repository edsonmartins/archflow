package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

import java.time.Duration;
import java.util.Map;

/**
 * Eventos do domínio INTERACTION para interações que requerem input do usuário.
 *
 * <p>Este domain trata de:
 * <ul>
 *   <li>Suspensão de conversas para input humano</li>
 *   <li>Renderização de formulários</li>
 *   <li>Retomada de conversas suspensas</li>
 *   <li>Cancelamento de interações</li>
 * </ul>
 *
 * <h3>Exemplos:</h3>
 * <pre>{@code
 * // Suspender para input
 * InteractionEvent.suspend("exec_123", "Need user confirmation", Duration.ofMinutes(5));
 *
 * // Renderizar formulário
 * InteractionEvent.form("user_info", Map.of(
 *     "fields", List.of(
 *         Map.of("name", "email", "type", "email", "required", true)
 *     )
 * ));
 *
 * // Retomar conversa
 * InteractionEvent.resume("exec_123", "resume_token_abc");
 * }</pre>
 */
public final class InteractionEvent {

    private InteractionEvent() {
        // Utilitário - não instanciar
    }

    /**
     * Cria um evento de suspensão de conversa.
     *
     * @param executionId ID da execução
     * @param reason Razão da suspensão
     * @param timeout Tempo limite para retomada
     * @return ArchflowEvent
     */
    public static ArchflowEvent suspend(String executionId, String reason, Duration timeout) {
        String resumeToken = generateResumeToken(executionId);

        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.SUSPEND)
                .executionId(executionId)
                .addData("reason", reason)
                .addData("resumeToken", resumeToken)
                .addData("timeoutMs", timeout.toMillis())
                .addData("expiresAt", System.currentTimeMillis() + timeout.toMillis())
                .build();
    }

    /**
     * Cria um evento de suspensão com dados de contexto.
     *
     * @param executionId ID da execução
     * @param reason Razão da suspensão
     * @param timeout Tempo limite para retomada
     * @param contextData Dados de contexto a serem preservados
     * @return ArchflowEvent
     */
    public static ArchflowEvent suspend(String executionId, String reason,
                                        Duration timeout, Map<String, Object> contextData) {
        String resumeToken = generateResumeToken(executionId);

        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.SUSPEND)
                .executionId(executionId)
                .addData("reason", reason)
                .addData("resumeToken", resumeToken)
                .addData("timeoutMs", timeout.toMillis())
                .addData("expiresAt", System.currentTimeMillis() + timeout.toMillis());

        if (contextData != null) {
            builder.addData("context", contextData);
        }

        return builder.build();
    }

    /**
     * Cria um evento de formulário para input do usuário.
     *
     * @param formId Identificador do formulário
     * @param fields Definição dos campos do formulário
     * @return ArchflowEvent
     */
    public static ArchflowEvent form(String formId, Map<String, Object> fields) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.FORM)
                .addData("formId", formId)
                .addData("fields", fields)
                .build();
    }

    /**
     * Cria um evento de formulário com executionId.
     *
     * @param formId Identificador do formulário
     * @param fields Definição dos campos
     * @param executionId ID da execução
     * @param submitUrl URL para submissão do formulário
     * @return ArchflowEvent
     */
    public static ArchflowEvent form(String formId, Map<String, Object> fields,
                                     String executionId, String submitUrl) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.FORM)
                .executionId(executionId)
                .addData("formId", formId)
                .addData("fields", fields)
                .addData("submitUrl", submitUrl)
                .build();
    }

    /**
     * Cria um evento de formulário com metadados completos.
     *
     * @param formId Identificador do formulário
     * @param title Título do formulário
     * @param description Descrição do formulário
     * @param fields Definição dos campos
     * @param executionId ID da execução
     * @param timeoutMs Timeout para submissão
     * @return ArchflowEvent
     */
    public static ArchflowEvent form(String formId, String title, String description,
                                     Map<String, Object> fields, String executionId, long timeoutMs) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.FORM)
                .executionId(executionId)
                .addData("formId", formId)
                .addData("title", title)
                .addData("description", description)
                .addData("fields", fields)
                .addData("timeoutMs", timeoutMs)
                .addData("expiresAt", System.currentTimeMillis() + timeoutMs)
                .build();
    }

    /**
     * Cria um evento de retomada de conversa.
     *
     * @param executionId ID da execução
     * @param resumeToken Token de retomada
     * @return ArchflowEvent
     */
    public static ArchflowEvent resume(String executionId, String resumeToken) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.RESUME)
                .executionId(executionId)
                .addData("resumeToken", resumeToken)
                .build();
    }

    /**
     * Cria um evento de retomada com dados do usuário.
     *
     * @param executionId ID da execução
     * @param resumeToken Token de retomada
     * @param userData Dados fornecidos pelo usuário
     * @return ArchflowEvent
     */
    public static ArchflowEvent resume(String executionId, String resumeToken,
                                      Map<String, Object> userData) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.RESUME)
                .executionId(executionId)
                .addData("resumeToken", resumeToken);

        if (userData != null) {
            builder.addData("userData", userData);
        }

        return builder.build();
    }

    /**
     * Cria um evento de cancelamento de interação.
     *
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent cancel(String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.CANCEL)
                .executionId(executionId)
                .build();
    }

    /**
     * Cria um evento de cancelamento com razão.
     *
     * @param executionId ID da execução
     * @param reason Razão do cancelamento
     * @return ArchflowEvent
     */
    public static ArchflowEvent cancel(String executionId, String reason) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.CANCEL)
                .executionId(executionId)
                .addData("reason", reason)
                .build();
    }

    /**
     * Cria um evento de erro de interação.
     *
     * @param executionId ID da execução
     * @param errorMessage Mensagem de erro
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String executionId, String errorMessage) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.ERROR)
                .executionId(executionId)
                .addData("error", errorMessage)
                .build();
    }

    /**
     * Cria um evento de timeout de interação.
     *
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent timeout(String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.ERROR)
                .executionId(executionId)
                .addData("error", "Interaction timed out")
                .addData("errorType", "TIMEOUT")
                .build();
    }

    /**
     * Gera um token de retomada.
     *
     * @param executionId ID da execução
     * @return Token de retomada
     */
    private static String generateResumeToken(String executionId) {
        return "resume_" + executionId + "_" + System.currentTimeMillis();
    }

    /**
     * Tipos de campo de formulário suportados.
     */
    public enum FieldType {
        TEXT, EMAIL, NUMBER, PASSWORD, DATE,
        SELECT, MULTISELECT, CHECKBOX, RADIO,
        TEXTAREA, FILE, HIDDEN
    }

    /**
     * Construtor para definição de campos de formulário.
     */
    public static class FieldBuilder {
        private final Map<String, Object> field;

        private FieldBuilder(String name, FieldType type) {
            this.field = Map.of(
                    "name", name,
                    "type", type.name().toLowerCase()
            );
        }

        /**
         * Cria um novo campo.
         */
        public static FieldBuilder field(String name, FieldType type) {
            return new FieldBuilder(name, type);
        }

        /**
         * Define o rótulo do campo.
         */
        public FieldBuilder label(String label) {
            Map<String, Object> newField = new java.util.LinkedHashMap<>(field);
            newField.put("label", label);
            return new FieldBuilder((String) newField.get("name"),
                                   FieldType.valueOf(((String) newField.get("type")).toUpperCase())) {
                @Override
                public Map<String, Object> build() {
                    return newField;
                }
            };
        }

        /**
         * Define se o campo é obrigatório.
         */
        public FieldBuilder required(boolean required) {
            Map<String, Object> newField = new java.util.LinkedHashMap<>(field);
            newField.put("required", required);
            return new FieldBuilder((String) newField.get("name"),
                                   FieldType.valueOf(((String) newField.get("type")).toUpperCase())) {
                @Override
                public Map<String, Object> build() {
                    return newField;
                }
            };
        }

        /**
         * Define um valor padrão.
         */
        public FieldBuilder defaultValue(Object value) {
            Map<String, Object> newField = new java.util.LinkedHashMap<>(field);
            newField.put("defaultValue", value);
            return new FieldBuilder((String) newField.get("name"),
                                   FieldType.valueOf(((String) newField.get("type")).toUpperCase())) {
                @Override
                public Map<String, Object> build() {
                    return newField;
                }
            };
        }

        /**
         * Constrói o mapa do campo.
         */
        public Map<String, Object> build() {
            return field;
        }
    }
}
