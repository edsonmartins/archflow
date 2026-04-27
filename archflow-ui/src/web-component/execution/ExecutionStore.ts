import type { ExecutionHistoryEntry } from './execution-types';

/**
 * In-memory store of past executions for the web-component history panel.
 *
 * The web component is framework-agnostic so we cannot reuse the React
 * Zustand store. Mirrors the small slice of API the panel needs:
 * append on completion, list, lookup by id, clear.
 */
export class ExecutionStore {
    private entries: ExecutionHistoryEntry[] = [];
    private maxSize: number;

    constructor(maxSize = 100) {
        this.maxSize = maxSize;
    }

    record(entry: ExecutionHistoryEntry): void {
        this.entries.push(entry);
        if (this.entries.length > this.maxSize) {
            this.entries = this.entries.slice(-this.maxSize);
        }
    }

    getHistory(): ExecutionHistoryEntry[] {
        return this.entries.slice();
    }

    getHistoryEntry(executionId: string): ExecutionHistoryEntry | undefined {
        return this.entries.find(e => e.executionId === executionId);
    }

    clearHistory(): void {
        this.entries = [];
    }
}
