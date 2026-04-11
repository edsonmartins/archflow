import { api } from './api';

export interface WorkflowYamlDto {
    id: string;
    yaml: string;
    version: string | null;
}

/**
 * Thin REST client for the YAML round-trip endpoints documented by
 * {@code WorkflowYamlController} in archflow-api. The actual binding
 * layer wires these to {@link br.com.archflow.api.workflow.WorkflowYamlBridge}.
 */
export const workflowYamlApi = {
    get: (id: string) => api.get<WorkflowYamlDto>(`/workflows/${encodeURIComponent(id)}/yaml`),

    update: (id: string, yaml: string) =>
        api.put<WorkflowYamlDto>(`/workflows/${encodeURIComponent(id)}/yaml`, {
            id,
            yaml,
            version: null,
        }),
};
