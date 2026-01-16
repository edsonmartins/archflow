package br.com.archflow.agent.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Emitter para streaming de eventos via SSE (Server-Sent Events).
 *
 * <p>Esta classe abstrai o envio de eventos no formato SSE,
 * permitindo streaming de ArchflowEvent para clientes conectados.
 *
 * <p>O formato SSE enviado é:
 * <pre>
 * event: message
 * data: {"domain":"chat","type":"delta",...}
 * </pre>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * EventStreamEmitter emitter = new EventStreamEmitter("exec_123");
 *
 * // Envia evento
 * emitter.send(ChatEvent.delta("Hello", "exec_123"));
 *
 * // Completa stream
 * emitter.complete();
 *
 * // Em caso de erro
 * emitter.completeWithError(exception);
 * }</pre>
 */
public class EventStreamEmitter {

    private static final Logger log = LoggerFactory.getLogger(EventStreamEmitter.class);

    private final String executionId;
    private final String emitterId;
    private final long createdAt;
    private volatile boolean completed = false;
    private final ConcurrentHashMap<String, Object> attributes;

    // Callbacks para integração com frameworks web (Spring, etc)
    private Consumer<ArchflowEvent> sendCallback;
    private Consumer<Throwable> errorCallback;
    private Runnable completeCallback;

    /**
     * Cria um novo emitter.
     *
     * @param executionId ID da execução associada
     */
    public EventStreamEmitter(String executionId) {
        this.executionId = executionId;
        this.emitterId = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.attributes = new ConcurrentHashMap<>();
    }

    /**
     * Cria um novo emitter com ID específico.
     *
     * @param executionId ID da execução
     * @param emitterId ID do emitter
     */
    public EventStreamEmitter(String executionId, String emitterId) {
        this.executionId = executionId;
        this.emitterId = emitterId;
        this.createdAt = System.currentTimeMillis();
        this.attributes = new ConcurrentHashMap<>();
    }

    /**
     * Envia um evento via SSE.
     *
     * @param event Evento a enviar
     * @throws IllegalStateException Se emitter já estiver completado
     */
    public void send(ArchflowEvent event) {
        if (completed) {
            throw new IllegalStateException("Emitter already completed");
        }

        log.trace("[{}] Sending event: {}:{}",
                executionId, event.getDomain().getValue(), event.getType().getValue());

        if (sendCallback != null) {
            sendCallback.accept(event);
        } else {
            log.debug("[{}] No send callback configured, event not sent", executionId);
        }
    }

    /**
     * Envia um evento com dados específicos.
     *
     * @param domain Domínio do evento
     * @param type Tipo do evento
     * @param data Dados do evento
     */
    public void send(ArchflowDomain domain, ArchflowEventType type, Object data) {
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(domain)
                .type(type)
                .executionId(executionId)
                .build();
        send(event);
    }

    /**
     * Envia um delta de chat (atalho).
     *
     * @param content Conteúdo do delta
     */
    public void sendDelta(String content) {
        send(br.com.archflow.agent.streaming.domain.ChatEvent.delta(content, executionId));
    }

    /**
     * Envia um heartbeat.
     */
    public void sendHeartbeat() {
        send(br.com.archflow.agent.streaming.domain.SystemEvent.heartbeat(executionId));
    }

    /**
     * Marca o stream como completo.
     */
    public void complete() {
        if (!completed) {
            completed = true;
            log.debug("[{}] Stream completed", executionId);
            if (completeCallback != null) {
                completeCallback.run();
            }
        }
    }

    /**
     * Marca o stream como completado com erro.
     *
     * @param error Exceção causadora
     */
    public void completeWithError(Throwable error) {
        if (!completed) {
            // Envia evento de erro antes de completar
            try {
                send(br.com.archflow.agent.streaming.domain.ChatEvent.error(executionId, error));
            } catch (Exception e) {
                log.warn("[{}] Failed to send error event", executionId, e);
            }

            completed = true;
            log.error("[{}] Stream completed with error: {}",
                    executionId, error.getMessage());

            if (errorCallback != null) {
                errorCallback.accept(error);
            } else if (completeCallback != null) {
                completeCallback.run();
            }
        }
    }

    /**
     * Retorna o ID da execução.
     *
     * @return executionId
     */
    public String getExecutionId() {
        return executionId;
    }

    /**
     * Retorna o ID do emitter.
     *
     * @return emitterId
     */
    public String getEmitterId() {
        return emitterId;
    }

    /**
     * Retorna o timestamp de criação.
     *
     * @return createdAt em ms
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Retorna se o emitter está completado.
     *
     * @return true se completado
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Define um atributo no emitter.
     *
     * @param key Chave
     * @param value Valor
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Retorna um atributo do emitter.
     *
     * @param key Chave
     * @return Valor ou null
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Retorna um atributo com tipo.
     *
     * @param key Chave
     * @param type Tipo do valor
     * @param <T> Tipo
     * @return Valor ou null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Configura callback para envio de eventos.
     * <p>Usado para integração com frameworks web.</p>
     *
     * @param callback Callback de envio
     * @return Esta instância
     */
    public EventStreamEmitter onSend(Consumer<ArchflowEvent> callback) {
        this.sendCallback = callback;
        return this;
    }

    /**
     * Configura callback para erro.
     *
     * @param callback Callback de erro
     * @return Esta instância
     */
    public EventStreamEmitter onError(Consumer<Throwable> callback) {
        this.errorCallback = callback;
        return this;
    }

    /**
     * Configura callback para conclusão.
     *
     * @param callback Callback de conclusão
     * @return Esta instância
     */
    public EventStreamEmitter onComplete(Runnable callback) {
        this.completeCallback = callback;
        return this;
    }

    /**
     * Retorna a duração desde a criação.
     *
     * @return Duração em ms
     */
    public long getDuration() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public String toString() {
        return "EventStreamEmitter{" +
                "emitterId='" + emitterId + '\'' +
                ", executionId='" + executionId + '\'' +
                ", completed=" + completed +
                ", duration=" + getDuration() + "ms" +
                '}';
    }
}
