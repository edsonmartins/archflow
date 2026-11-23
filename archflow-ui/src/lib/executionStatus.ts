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
