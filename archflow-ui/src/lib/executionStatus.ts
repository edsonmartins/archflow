/**
 * Single source of truth for execution-status badge colors (Mantine color
 * names). Previously duplicated byte-for-byte in the executions list and
 * the dashboard — a new status (e.g. TIMEOUT) must be added exactly once.
 */
export const EXECUTION_STATUS_COLOR: Record<string, string> = {
    RUNNING:   'blue',
    COMPLETED: 'teal',
    FAILED:    'red',
    PAUSED:    'yellow',
    CANCELLED: 'gray',
    // Recusada no portão de aprovação (por alguém ou pelo prazo): acabou, mas não falhou.
    STOPPED:   'orange',
}

/**
 * Status em que a execução acabou de vez — o mesmo conjunto que os stores do backend tratam como
 * terminal (`InMemoryWorkflowRuntimeStore`/`JdbcWorkflowRuntimeStore`: COMPLETED, FAILED, STOPPED,
 * CANCELLED). É uma lista do que É terminal, não "tudo menos RUNNING": PAUSED e AWAITING_APPROVAL
 * (aprovação humana) também não são RUNNING, e os passos depois do portão ainda vão rodar.
 */
export const TERMINAL_EXECUTION_STATUSES: ReadonlySet<string> =
    new Set(['COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED'])

export function isTerminalExecutionStatus(status: string | null | undefined): boolean {
    return status != null && TERMINAL_EXECUTION_STATUSES.has(status)
}

/**
 * De quanto em quanto tempo o detalhe de uma execução se recarrega, ou `null` para não
 * recarregar. Uma execução PAUSED (aprovação humana) também muda sozinha: quem decide é a fila de
 * Aprovações, em outra tela, e a execução retoma e termina sem que esta página faça nada. Sem o
 * polling, o detalhe aberto mostrava PAUSED até alguém apertar F5. O intervalo é maior que o de
 * RUNNING porque a espera por uma pessoa dura minutos ou horas.
 */
export function executionPollIntervalMs(status: string | null | undefined): number | null {
    if (status === 'RUNNING') return 2000
    if (status === 'PAUSED' || status === 'AWAITING_APPROVAL') return 5000
    return null
}
