package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

/**
 * Eventos do domínio SYSTEM para eventos do sistema.
 *
 * <p>Este domain trata de:
 * <ul>
 *   <li>Conexão e desconexão de clientes</li>
 *   <li>Erros do sistema</li>
 *   <li>Heartbeat de keep-alive</li>
 *   <li>Notificações do sistema</li>
 * </ul>
 *
 * <h3>Exemplos:</h3>
 * <pre>{@code
 * // Cliente conectado
 * SystemEvent.connected("client_123", "session_abc");
 *
 * // Heartbeat
 * SystemEvent.heartbeat();
 *
 * // Erro do sistema
 * SystemEvent.error("Database connection failed", "CONNECTION_ERROR");
 * }</pre>
 */
public final class SystemEvent {

    private SystemEvent() {
        // Utilitário - não instanciar
    }

    /**
     * Cria um evento de cliente conectado.
     *
     * @param clientId ID do cliente
     * @param sessionId ID da sessão
     * @return ArchflowEvent
     */
    public static ArchflowEvent connected(String clientId, String sessionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.CONNECTED)
                .addData("clientId", clientId)
                .addData("sessionId", sessionId)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de cliente conectado com detalhes.
     *
     * @param clientId ID do cliente
     * @param sessionId ID da sessão
     * @param userAgent User agent do cliente
     * @param ipAddress IP address do cliente
     * @return ArchflowEvent
     */
    public static ArchflowEvent connected(String clientId, String sessionId,
                                         String userAgent, String ipAddress) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.CONNECTED)
                .addData("clientId", clientId)
                .addData("sessionId", sessionId)
                .addData("userAgent", userAgent)
                .addData("ipAddress", ipAddress)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de cliente desconectado.
     *
     * @param clientId ID do cliente
     * @param sessionId ID da sessão
     * @return ArchflowEvent
     */
    public static ArchflowEvent disconnected(String clientId, String sessionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.DISCONNECTED)
                .addData("clientId", clientId)
                .addData("sessionId", sessionId)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de cliente desconectado com razão.
     *
     * @param clientId ID do cliente
     * @param sessionId ID da sessão
     * @param reason Razão da desconexão
     * @param durationMs Duração da conexão em ms
     * @return ArchflowEvent
     */
    public static ArchflowEvent disconnected(String clientId, String sessionId,
                                            String reason, long durationMs) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.DISCONNECTED)
                .addData("clientId", clientId)
                .addData("sessionId", sessionId)
                .addData("reason", reason)
                .addData("durationMs", durationMs)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de heartbeat.
     *
     * @return ArchflowEvent
     */
    public static ArchflowEvent heartbeat() {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.HEARTBEAT)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de heartbeat com ID de sessão.
     *
     * @param sessionId ID da sessão
     * @return ArchflowEvent
     */
    public static ArchflowEvent heartbeat(String sessionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.HEARTBEAT)
                .addData("sessionId", sessionId)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de erro do sistema.
     *
     * @param message Mensagem de erro
     * @param errorCode Código do erro
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String message, String errorCode) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.ERROR)
                .addData("error", message)
                .addData("errorCode", errorCode)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de erro com exceção.
     *
     * @param message Mensagem de erro
     * @param errorCode Código do erro
     * @param throwable Exceção
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String message, String errorCode, Throwable throwable) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.ERROR)
                .addData("error", message)
                .addData("errorCode", errorCode)
                .addData("exception", throwable.getClass().getSimpleName())
                .addData("exceptionMessage", throwable.getMessage())
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de notificação do sistema.
     *
     * @param message Mensagem da notificação
     * @return ArchflowEvent
     */
    public static ArchflowEvent notification(String message) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .addData("message", message)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Cria um evento de notificação com nível.
     *
     * @param message Mensagem da notificação
     * @param level Nível (INFO, WARN, ERROR)
     * @return ArchflowEvent
     */
    public static ArchflowEvent notification(String message, String level) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .addData("message", message)
                .addData("level", level)
                .addData("timestamp", System.currentTimeMillis())
                .build();
    }

    /**
     * Níveis de notificação.
     */
    public enum NotificationLevel {
        INFO, WARN, ERROR, SUCCESS
    }

    /**
     * Tipos de erro de sistema.
     */
    public enum SystemError {
        CONNECTION_ERROR("CONNECTION_ERROR"),
        TIMEOUT("TIMEOUT"),
        RATE_LIMIT("RATE_LIMIT"),
        INTERNAL_ERROR("INTERNAL_ERROR"),
        SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE"),
        AUTHENTICATION_FAILED("AUTHENTICATION_FAILED"),
        AUTHORIZATION_FAILED("AUTHORIZATION_FAILED");

        private final String code;

        SystemError(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
