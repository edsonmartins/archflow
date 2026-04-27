/**
 * Execution lifecycle types for the web-component history panel.
 *
 * Kept intentionally framework-agnostic (no React, no Mantine) so the
 * panel can render inside the Shadow DOM of `<archflow-designer>`.
 */

export enum ExecutionState {
    IDLE = 'IDLE',
    RUNNING = 'RUNNING',
    PAUSED = 'PAUSED',
    COMPLETED = 'COMPLETED',
    FAILED = 'FAILED',
    CANCELLED = 'CANCELLED',
}

export interface ExecutionMetrics {
    nodesExecuted?: number;
    nodesSucceeded?: number;
    nodesFailed?: number;
    totalTokens?: number;
}

export interface ExecutionError {
    nodeId: string;
    type: string;
    message: string;
}

export interface ExecutionResult {
    output?: Record<string, unknown>;
    errors?: ExecutionError[];
    metrics?: ExecutionMetrics;
}

export interface ExecutionHistoryEntry {
    executionId: string;
    workflowId: string;
    workflowName: string;
    state: ExecutionState;
    startTime: number;
    duration?: number;
    result?: ExecutionResult;
}

export function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const seconds = ms / 1000;
    if (seconds < 60) return `${seconds.toFixed(1)}s`;
    const minutes = Math.floor(seconds / 60);
    const remSeconds = Math.round(seconds - minutes * 60);
    return `${minutes}m ${remSeconds}s`;
}

export function formatTimestamp(epochMs: number): string {
    const date = new Date(epochMs);
    const now = Date.now();
    const diff = now - epochMs;
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return date.toLocaleDateString();
}
