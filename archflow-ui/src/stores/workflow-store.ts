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
    fetchExecutions: (workflowId?: string) => Promise<void>;
    clearCurrent: () => void;
    clearError: () => void;
}

export const useWorkflowStore = create<WorkflowState>((set) => ({
    workflows: [],
    currentWorkflow: null,
    executions: [],
    loading: false,
    error: null,

    fetchWorkflows: async () => {
        set({ loading: true, error: null });
        try {
            const workflows = await workflowApi.list();
            set({ workflows, loading: false });
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to load workflows' });
        }
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

    fetchExecutions: async (workflowId) => {
        set({ loading: true, error: null });
        try {
            const executions = await executionApi.list(workflowId);
            set({ executions, loading: false });
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Failed to load executions' });
        }
    },

    clearCurrent: () => set({ currentWorkflow: null }),
    clearError: () => set({ error: null }),
}));
