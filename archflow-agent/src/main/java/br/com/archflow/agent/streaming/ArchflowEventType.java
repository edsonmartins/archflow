package br.com.archflow.agent.streaming;

/**
 * Tipos de eventos do streaming protocol.
 *
 * <p>Cada tipo define o propósito e a estrutura esperada do evento.
 *
 * @see ArchflowEvent
 */
public enum ArchflowEventType {

    // ========================
    // Eventos Universais (aplicam a todos os domains)
    // ========================

    /**
     * Início de um fluxo de eventos.
     */
    START("start"),

    /**
     * Fim de um fluxo de eventos.
     */
    END("end"),

    /**
     * Erro ocorreu durante processamento.
     */
    ERROR("error"),

    // ========================
    // Eventos do Domain CHAT
    // ========================

    /**
     * Chunk de mensagem streaming (delta).
     */
    DELTA("delta"),

    /**
     * Mensagem completa.
     */
    MESSAGE("message"),

    // ========================
    // Eventos do Domain THINKING
    // ========================

    /**
     * Chunk do processo de raciocínio.
     */
    THINKING("thinking"),

    /**
     * Passo de reflexão do modelo.
     */
    REFLECTION("reflection"),

    /**
     * Verificação interna do modelo.
     */
    VERIFICATION("verification"),

    // ========================
    // Eventos do Domain TOOL
    // ========================

    /**
     * Início de execução de tool.
     */
    TOOL_START("tool_start"),

    /**
     * Progresso de execução de tool.
     */
    PROGRESS("progress"),

    /**
     * Resultado de execução de tool.
     */
    RESULT("result"),

    /**
     * Erro de execução de tool.
     */
    TOOL_ERROR("tool_error"),

    // ========================
    // Eventos do Domain AUDIT
    // ========================

    /**
     * Entrada de trace para debugging.
     */
    TRACE("trace"),

    /**
     * Span de tracing distribuído.
     */
    SPAN("span"),

    /**
     * Métrica de execução.
     */
    METRIC("metric"),

    /**
     * Entrada de log.
     */
    LOG("log"),

    // ========================
    // Eventos do Domain INTERACTION
    // ========================

    /**
     * Suspensão da conversa para input do usuário.
     */
    SUSPEND("suspend"),

    /**
     * Renderização de formulário para input.
     */
    FORM("form"),

    /**
     * Retomada da conversa suspensa.
     */
    RESUME("resume"),

    /**
     * Cancelamento de interação.
     */
    CANCEL("cancel"),

    // ========================
    // Eventos do Domain SYSTEM
    // ========================

    /**
     * Cliente conectado ao stream.
     */
    CONNECTED("connected"),

    /**
     * Cliente desconectado do stream.
     */
    DISCONNECTED("disconnected"),

    /**
     * Heartbeat de keep-alive.
     */
    HEARTBEAT("heartbeat");

    private final String value;

    ArchflowEventType(String value) {
        this.value = value;
    }

    /**
     * Retorna o valor string do tipo de evento.
     *
     * @return Valor do tipo
     */
    public String getValue() {
        return value;
    }

    /**
     * Retorna o tipo de evento a partir do valor string.
     *
     * @param value Valor do tipo
     * @return ArchflowEventType correspondente
     * @throws IllegalArgumentException Se valor não for válido
     */
    public static ArchflowEventType fromValue(String value) {
        for (ArchflowEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }
}
