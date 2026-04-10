package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementação imutável do {@link ExecutionContext} conforme RFC-005 v2.
 *
 * <p>Todos os campos são de primeira classe e imutáveis. Mutações retornam
 * novas instâncias — o contexto original nunca é alterado.
 *
 * <p>Esta é a implementação recomendada para produção multi-tenant.
 * {@link DefaultExecutionContext} é mantido apenas para backward compatibility.
 *
 * <p>Exemplo:
 * <pre>{@code
 * var ctx = ImmutableExecutionContext.builder()
 *     .tenantId("tenant-1")
 *     .userId("user-1")
 *     .sessionId("session-1")
 *     .chatMemory(memory)
 *     .variable("playbookConfig", config)
 *     .build();
 *
 * // Mutações retornam nova instância
 * var updated = ctx.withVariable("key", "value");
 * var withState = ctx.withState(newState);
 * }</pre>
 *
 * @since 2.0.0
 */
public record ImmutableExecutionContext(
        String tenantId,
        String userId,
        String sessionId,
        String requestId,
        ChatMemory chatMemory,
        FlowState flowState,
        Map<String, Object> variables,
        ExecutionMetrics metrics
) implements ExecutionContext {

    public ImmutableExecutionContext {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (requestId == null) requestId = UUID.randomUUID().toString();
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    // ── ExecutionContext interface ──────────────────────────────────────

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(variables.get(key));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void set(String key, Object value) {
        throw new UnsupportedOperationException(
                "ImmutableExecutionContext does not support set(). Use withVariable() instead.");
    }

    @Override
    public ExecutionContext withVariable(String key, Object value) {
        Map<String, Object> newVars = new HashMap<>(this.variables);
        newVars.put(key, value);
        return new ImmutableExecutionContext(
                tenantId, userId, sessionId, requestId,
                chatMemory, flowState, newVars, metrics);
    }

    @Override
    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    @Override
    public ExecutionMetrics getMetrics() {
        return metrics != null ? metrics : new ExecutionMetrics(0, 0, 0.0, Map.of());
    }

    @Override
    public FlowState getState() {
        return flowState;
    }

    @Override
    public void setState(FlowState state) {
        throw new UnsupportedOperationException(
                "ImmutableExecutionContext does not support setState(). Use withState() instead.");
    }

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public String getUserId() {
        return userId;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getRequestId() {
        return requestId;
    }

    @Override
    public Map<String, Object> getVariables() {
        return variables; // already unmodifiable via Map.copyOf in constructor
    }

    @Override
    public ExecutionContext snapshot() {
        return this; // already immutable
    }

    // ── Métodos imutáveis de rebuild ────────────────────────────────────

    /**
     * Retorna nova instância com o FlowState atualizado.
     */
    public ImmutableExecutionContext withState(FlowState newState) {
        return new ImmutableExecutionContext(
                tenantId, userId, sessionId, requestId,
                chatMemory, newState, variables, metrics);
    }

    /**
     * Retorna nova instância com métricas atualizadas.
     */
    public ImmutableExecutionContext withMetrics(ExecutionMetrics newMetrics) {
        return new ImmutableExecutionContext(
                tenantId, userId, sessionId, requestId,
                chatMemory, flowState, variables, newMetrics);
    }

    /**
     * Retorna nova instância com ChatMemory atualizado.
     */
    public ImmutableExecutionContext withChatMemory(ChatMemory newMemory) {
        return new ImmutableExecutionContext(
                tenantId, userId, sessionId, requestId,
                newMemory, flowState, variables, metrics);
    }

    /**
     * Retorna nova instância com todas as variáveis substituídas.
     */
    public ImmutableExecutionContext withVariables(Map<String, Object> newVariables) {
        return new ImmutableExecutionContext(
                tenantId, userId, sessionId, requestId,
                chatMemory, flowState, newVariables, metrics);
    }

    // ── Builder ────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tenantId = "SYSTEM";
        private String userId;
        private String sessionId;
        private String requestId;
        private ChatMemory chatMemory;
        private FlowState flowState;
        private final Map<String, Object> variables = new HashMap<>();
        private ExecutionMetrics metrics;

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder flowState(FlowState flowState) {
            this.flowState = flowState;
            return this;
        }

        public Builder variable(String key, Object value) {
            this.variables.put(key, value);
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            this.variables.putAll(variables);
            return this;
        }

        public Builder metrics(ExecutionMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public ImmutableExecutionContext build() {
            return new ImmutableExecutionContext(
                    tenantId, userId, sessionId, requestId,
                    chatMemory, flowState, variables, metrics);
        }
    }
}
