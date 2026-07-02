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
