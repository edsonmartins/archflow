package br.com.archflow.agent.tool;

import br.com.archflow.model.engine.ExecutionContext;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contexto de execução de uma tool.
 *
 * <p>Este objeto contém todas as informações relevantes sobre a execução
 * de uma tool específica e é passado através da cadeia de interceptores.
 */
public class ToolContext {

    /**
     * Chave do atributo que contém o mapa de tools registradas.
     * O valor deve ser um {@code Map<String, ToolFunction>}.
     */
    public static final String TOOLS_MAP_KEY = "_archflow_tools_map";

    private final String executionId;
    private final String toolName;
    private final Object input;
    private final ExecutionContext executionContext;
    private final Instant startTime;
    private final Map<String, Object> attributes;

    private ToolResult result;
    private Throwable error;
    private Instant endTime;
    private boolean cached;

    private ToolContext(Builder builder) {
        this.executionId = builder.executionId;
        this.toolName = builder.toolName;
        this.input = builder.input;
        this.executionContext = builder.executionContext;
        this.startTime = Instant.now();
        this.attributes = new ConcurrentHashMap<>();
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getToolName() {
        return toolName;
    }

    public Object getInput() {
        return input;
    }

    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public long getDurationMillis() {
        return endTime != null
                ? endTime.toEpochMilli() - startTime.toEpochMilli()
                : System.currentTimeMillis() - startTime.toEpochMilli();
    }

    public ToolResult getResult() {
        return result;
    }

    public void setResult(ToolResult result) {
        this.result = result;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

    /**
     * Armazena um atributo arbitrário no contexto.
     *
     * @param key  Chave do atributo
     * @param value Valor do atributo
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Recupera um atributo do contexto.
     *
     * @param key Chave do atributo
     * @param <T> Tipo do valor
     * @return Valor do atributo, ou null se não existir
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Recupera um atributo do contexto com valor padrão.
     *
     * @param key         Chave do atributo
     * @param defaultValue Valor padrão se não existir
     * @param <T>         Tipo do valor
     * @return Valor do atributo, ou defaultValue
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        return (T) attributes.getOrDefault(key, defaultValue);
    }

    /**
     * Verifica se um atributo existe no contexto.
     *
     * @param key Chave do atributo
     * @return true se o atributo existe
     */
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    /**
     * Remove um atributo do contexto.
     *
     * @param key Chave do atributo
     * @return Valor removido, ou null se não existia
     */
    @SuppressWarnings("unchecked")
    public <T> T removeAttribute(String key) {
        return (T) attributes.remove(key);
    }

    public Map<String, Object> getAttributes() {
        return new ConcurrentHashMap<>(attributes);
    }

    /**
     * Cria uma cópia do contexto para ser usado em subprocessos.
     *
     * @return Nova cópia do contexto
     */
    public ToolContext copy() {
        ToolContext copy = builder()
                .executionId(this.executionId)
                .toolName(this.toolName)
                .input(this.input)
                .executionContext(this.executionContext)
                .build();
        copy.attributes.putAll(this.attributes);
        return copy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String executionId;
        private String toolName;
        private Object input;
        private ExecutionContext executionContext;

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder input(Object input) {
            this.input = input;
            return this;
        }

        public Builder executionContext(ExecutionContext executionContext) {
            this.executionContext = executionContext;
            return this;
        }

        public ToolContext build() {
            if (executionId == null) {
                throw new IllegalArgumentException("executionId is required");
            }
            if (toolName == null) {
                throw new IllegalArgumentException("toolName is required");
            }
            return new ToolContext(this);
        }
    }
}
