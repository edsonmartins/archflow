import { api, ifMatch } from './api';

export interface WorkflowYamlDto {
    id: string;
    yaml: string;
    version: string | null;
    /** O `updatedAt` do documento de onde o YAML saiu — o If-Match do próximo Salvar YAML (RCHFLW-174). */
    updatedAt?: string | null;
}

/**
 * Thin REST client for the YAML round-trip endpoints documented by
 * {@code WorkflowYamlController} in archflow-api. The actual binding
 * layer wires these to {@link br.com.archflow.api.workflow.WorkflowYamlBridge}.
 */
export const workflowYamlApi = {
    get: (id: string) => api.get<WorkflowYamlDto>(`/workflows/${encodeURIComponent(id)}/yaml`),

    /**
     * Com `expectedUpdatedAt` (o `updatedAt` do GET que trouxe este YAML), o servidor só grava se o fluxo continua
     * nessa versão — senão 409, como o Salvar do canvas (RCHFLW-174). Sem ele, grava por cima: o "Sobrescrever" do
     * aviso de conflito.
     */
    update: (id: string, yaml: string, expectedUpdatedAt?: string | null) =>
        api.put<WorkflowYamlDto>(`/workflows/${encodeURIComponent(id)}/yaml`, {
            id,
            yaml,
            version: null,
        }, ifMatch(expectedUpdatedAt)),
};
