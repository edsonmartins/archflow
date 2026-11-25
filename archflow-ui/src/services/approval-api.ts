import { api } from './api';

export type ApprovalStatus =
    | 'PENDING'
    | 'APPROVED'
    | 'REJECTED'
    | 'EDITED'
    | 'EXPIRED';

export interface ApprovalResponse {
    requestId: string;
    tenantId: string;
    flowId: string;
    stepId: string | null;
    status: ApprovalStatus;
    description: string | null;
    proposal: unknown;
    createdAt: string | null;
    expiresAt: string | null;
}

export interface ApprovalSubmitRequest {
    tenantId: string;
    decision: 'APPROVED' | 'REJECTED' | 'EDITED';
    editedPayload?: unknown;
    responderId?: string;
    comment?: string;
}

/**
 * Client for the Human-in-the-Loop approval queue exposed by
 * archflow-api's {@code ApprovalController}. Mirrors the tenant-scoped
 * list + detail + submit + count surface used by the admin UI.
 */
export const approvalApi = {
    // tenantId ausente = o servidor usa o tenant da requisição (ver approvalTenantParam).
    listPending: (tenantId?: string) =>
        api.get<ApprovalResponse[]>(`/approvals/pending${tenantQuery(tenantId)}`),

    get: (requestId: string) =>
        api.get<ApprovalResponse>(`/approvals/${encodeURIComponent(requestId)}`),

    submit: (requestId: string, request: ApprovalSubmitRequest) =>
        api.post<ApprovalResponse>(
            `/approvals/${encodeURIComponent(requestId)}`,
            request,
        ),

    pendingCount: (tenantId?: string) =>
        api.get<{ count: number }>(`/approvals/pending/count${tenantQuery(tenantId)}`),
};

function tenantQuery(tenantId?: string): string {
    return tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';
}
