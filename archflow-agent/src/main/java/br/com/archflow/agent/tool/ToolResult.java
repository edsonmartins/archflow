package br.com.archflow.agent.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Resultado da execução de uma tool.
 *
 * @param <T> Tipo do resultado
 */
public class ToolResult<T> {

    private final T data;
    private final Status status;
    private final String message;
    private final Throwable error;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    private ToolResult(Builder<T> builder) {
        this.data = builder.data;
        this.status = builder.status;
        this.message = builder.message;
        this.error = builder.error;
        this.timestamp = Instant.now();
        this.metadata = builder.metadata;
    }

    /**
     * Cria um resultado de sucesso.
     *
     * @param data Dados retornados pela tool
     * @param <T>  Tipo dos dados
     * @return Resultado de sucesso
     */
    public static <T> ToolResult<T> success(T data) {
        return new Builder<T>()
                .data(data)
                .status(Status.SUCCESS)
                .build();
    }

    /**
     * Cria um resultado de sucesso com mensagem.
     *
     * @param data    Dados retornados pela tool
     * @param message Mensagem descritiva
     * @param <T>     Tipo dos dados
     * @return Resultado de sucesso
     */
    public static <T> ToolResult<T> success(T data, String message) {
        return new Builder<T>()
                .data(data)
                .status(Status.SUCCESS)
                .message(message)
                .build();
    }

    /**
     * Cria um resultado de erro.
     *
     * @param error Exceção ocorrida
     * @param <T>   Tipo dos dados
     * @return Resultado de erro
     */
    public static <T> ToolResult<T> error(Throwable error) {
        return new Builder<T>()
                .status(Status.ERROR)
                .error(error)
                .message(error.getMessage())
                .build();
    }

    /**
     * Cria um resultado de erro com mensagem customizada.
     *
     * @param message Mensagem de erro
     * @param error   Exceção ocorrida
     * @param <T>     Tipo dos dados
     * @return Resultado de erro
     */
    public static <T> ToolResult<T> error(String message, Throwable error) {
        return new Builder<T>()
                .status(Status.ERROR)
                .error(error)
                .message(message)
                .build();
    }

    /**
     * Cria um resultado vazio (para void tools).
     *
     * @param <T> Tipo dos dados
     * @return Resultado vazio
     */
    @SuppressWarnings("unchecked")
    public static <T> ToolResult<T> empty() {
        return (ToolResult<T>) new Builder<>()
                .data(null)
                .status(Status.SUCCESS)
                .build();
    }

    public Optional<T> getData() {
        return Optional.ofNullable(data);
    }

    public Status getStatus() {
        return status;
    }

    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    public Optional<Throwable> getError() {
        return Optional.ofNullable(error);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private T data;
        private Status status = Status.SUCCESS;
        private String message;
        private Throwable error;
        private Map<String, Object> metadata = Map.of();

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> status(Status status) {
            this.status = status;
            return this;
        }

        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        public Builder<T> error(Throwable error) {
            this.error = error;
            return this;
        }

        public Builder<T> metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ToolResult<T> build() {
            return new ToolResult<>(this);
        }
    }

    /**
     * Status do resultado da execução.
     */
    public enum Status {
        /** Execução bem-sucedida */
        SUCCESS,
        /** Execução com erro */
        ERROR,
        /** Execução interrompida */
        INTERRUPTED,
        /** Execução pulada (e.g., cache hit) */
        SKIPPED
    }
}
