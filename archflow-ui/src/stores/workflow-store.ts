import { create } from 'zustand';
import { workflowApi, executionApi } from '../services/api';
import type { WorkflowSummary, WorkflowDetail, ExecutionSummary } from '../services/api';

interface WorkflowState {
    workflows: WorkflowSummary[];
    currentWorkflow: WorkflowDetail | null;
    executions: ExecutionSummary[];
    loading: boolean;
    error: string | null;

    fetchWorkflows: () => Promise<void>;
    fetchWorkflow: (id: string) => Promise<void>;
    createWorkflow: (workflow: Partial<WorkflowDetail>) => Promise<WorkflowDetail>;
    updateWorkflow: (id: string, workflow: Partial<WorkflowDetail>) => Promise<void>;
    deleteWorkflow: (id: string) => Promise<void>;
    executeWorkflow: (id: string, input?: Record<string, unknown>) => Promise<string>;
    fetchExecutions: (workflowId?: string, limit?: number) => Promise<void>;
    /**
     * Patches the flow-level LLM defaults on the in-memory currentWorkflow.
     * Does NOT hit the backend — the editor's Save persists currentWorkflow.
     * These defaults are the "flow" tier of the model inheritance chain.
     */
    patchFlowLlmConfig: (patch: Record<string, unknown>) => void;
    clearCurrent: () => void;
    clearError: () => void;
}

// Several always-mounted consumers (layout search, dashboard, copilot
// context) ask for the workflow list on mount — collapse concurrent
// requests into a single GET instead of firing one per consumer.
let workflowsFetchInFlight: Promise<void> | null = null;

export const useWorkflowStore = create<WorkflowState>((set) => ({
    workflows: [],
    currentWorkflow: null,
    executions: [],
    loading: false,
    error: null,

    fetchWorkflows: async () => {
        if (workflowsFetchInFlight) return workflowsFetchInFlight;
        set({ loading: true, error: null });
        workflowsFetchInFlight = (async () => {
            try {
                const workflows = await workflowApi.list();
                set({ workflows, loading: false });
            } catch (e) {
                set({ loading: false, error: e instanceof Error ? e.message : 'Failed to load workflows' });
            } finally {
                workflowsFetchInFlight = null;
            }
        })();
        return workflowsFetchInFlight;
    },

    fetchWorkflow: async (id) => {
        set({ loading: true, error: null });
        try {
            const workflow = await workflowApi.get(id);
            set({ currentWorkflow: workflow, loading: false });
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to load workflow' });
        }
    },

    createWorkflow: async (workflow) => {
        set({ loading: true, error: null });
        try {
            const created = await workflowApi.create(workflow);
            set((state) => ({
                workflows: [...state.workflows, {
                    id: created.id,
                    name: created.metadata.name,
                    description: created.metadata.description,
                    version: created.metadata.version,
                    status: 'draft',
                    updatedAt: new Date().toISOString(),
                    stepCount: created.steps.length,
                }],
                loading: false,
            }));
            return created;
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to create workflow' });
            throw e;
        }
    },

    updateWorkflow: async (id, workflow) => {
        set({ loading: true, error: null });
        try {
            const updated = await workflowApi.update(id, workflow);
            set((state) => ({
                currentWorkflow: updated,
                workflows: state.workflows.map((w) =>
                    w.id === id ? { ...w, name: updated.metadata.name, updatedAt: new Date().toISOString() } : w
                ),
                loading: false,
            }));
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to update workflow' });
            // Rethrow (like createWorkflow) so callers can block dependent
            // actions — the editor must not report "saved" or run a stale
            // version when the PUT failed.
            throw e;
        }
    },

    deleteWorkflow: async (id) => {
        set({ loading: true, error: null });
        try {
            await workflowApi.delete(id);
            set((state) => ({
                workflows: state.workflows.filter((w) => w.id !== id),
                currentWorkflow: state.currentWorkflow?.id === id ? null : state.currentWorkflow,
                loading: false,
            }));
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to delete workflow' });
        }
    },

    executeWorkflow: async (id, input) => {
        set({ loading: true, error: null });
        try {
            const { executionId } = await workflowApi.execute(id, input);
            set({ loading: false });
            return executionId;
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to execute workflow' });
            throw e;
        }
    },

    fetchExecutions: async (workflowId, limit) => {
        set({ loading: true, error: null });
        try {
            const executions = await executionApi.list({ workflowId, limit });
            set({ executions, loading: false });
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to load executions' });
        }
    },

    patchFlowLlmConfig: (patch) => {
        set((state) => {
            if (!state.currentWorkflow) return {};
            const cfg = (state.currentWorkflow.configuration ?? {}) as Record<string, unknown>;
            const llmConfig = { ...(cfg.llmConfig as Record<string, unknown> ?? {}), ...patch };
            return {
                currentWorkflow: {
                    ...state.currentWorkflow,
                    configuration: { ...cfg, llmConfig },
                },
            };
        });
    },

    clearCurrent: () => set({ currentWorkflow: null }),
    clearError: () => set({ error: null }),
}));
