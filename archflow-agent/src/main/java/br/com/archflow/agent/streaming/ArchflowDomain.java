package br.com.archflow.agent.streaming;

/**
 * Domínios de eventos do streaming protocol.
 *
 * <p>Cada domínio representa uma categoria de eventos com semântica específica.
 *
 * @see ArchflowEvent
 */
public enum ArchflowDomain {

    /**
     * Domínio para mensagens do modelo de linguagem.
     *
     * <p>Eventos neste domínio incluem:
     * <ul>
     *   <li>delta - Chunk de resposta streaming</li>
     *   <li>message - Mensagem completa</li>
     *   <li>start - Início da resposta</li>
     *   <li>end - Fim da resposta</li>
     * </ul>
     */
    CHAT("chat"),

    /**
     * Domínio para raciocínio de modelos reasoning (o1, o3, etc).
     *
     * <p>Eventos neste domínio incluem:
     * <ul>
     *   <li>thinking - Chunk do processo de raciocínio</li>
     *   <li>reflection - Passos de reflexão</li>
     *   <li>verification - Verificações internas</li>
     * </ul>
     */
    THINKING("thinking"),

    /**
     * Domínio para execução de tools/funções.
     *
     * <p>Eventos neste domínio incluem:
     * <ul>
     *   <li>start - Início da execução da tool</li>
     *   <li>progress - Progresso da execução</li>
     *   <li>result - Resultado da execução</li>
     *   <li>error - Erro na execução</li>
     * </ul>
     */
    TOOL("tool"),

    /**
     * Domínio para tracing e debugging.
     *
     * <p>Eventos neste domínio incluem:
     * <ul>
     *   <li>trace - Trace de execução</li>
     *   <li>span - Span de tracing</li>
     *   <li>metric - Métricas de execução</li>
     *   <li>log - Log entries</li>
     * </ul>
     */
    AUDIT("audit"),

    /**
     * Domínio para interações que requerem input do usuário.
     *
     * <p>Eventos neste domínio incluem:
     * <ul>
     *   <li>suspend - Suspensão da conversa para input</li>
     *   <li>form - Renderização de formulário</li>
     *   <li>resume - Retomada da conversa</li>
     *   <li>cancel - Cancelamento da interação</li>
     * </ul>
     */
    INTERACTION("interaction"),

    /**
     * Domínio para eventos do sistema.
     *
     * <p>Eventos neste domínio incluem:
     * <ul>
     *   <li>connected - Cliente conectado</li>
     *   <li>disconnected - Cliente desconectado</li>
     *   <li>error - Erro do sistema</li>
     *   <li>heartbeat - Heartbeat de keep-alive</li>
     * </ul>
     */
    SYSTEM("system");

    private final String value;

    ArchflowDomain(String value) {
        this.value = value;
    }

    /**
     * Retorna o valor string do domínio.
     *
     * @return Valor do domínio
     */
    public String getValue() {
        return value;
    }

    /**
     * Retorna o domínio a partir do valor string.
     *
     * @param value Valor do domínio
     * @return ArchflowDomain correspondente
     * @throws IllegalArgumentException Se valor não for válido
     */
    public static ArchflowDomain fromValue(String value) {
        for (ArchflowDomain domain : values()) {
            if (domain.value.equals(value)) {
                return domain;
            }
        }
        throw new IllegalArgumentException("Unknown domain: " + value);
    }
}
