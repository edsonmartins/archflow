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
    /** Título configurado no nó de aprovação; nulo quando o nó não tem. */
    title: string | null;
    description: string | null;
    proposal: unknown;
    createdAt: string | null;
    expiresAt: string | null;
    /**
     * Quem decidiu, quando e por quê — lidos do estado durável do motor, nulos enquanto pendente.
     * O decisor é o usuário autenticado resolvido pelo servidor, nunca um valor enviado pelo cliente.
     */
    decidedBy?: string | null;
    decidedAt?: string | null;
    comment?: string | null;
}

export interface ApprovalSubmitRequest {
    tenantId: string;
    decision: 'APPROVED' | 'REJECTED' | 'EDITED';
    editedPayload?: unknown;
    // Sem responderId: quem decide é o usuário autenticado, resolvido pelo servidor a partir do token.
    // A UI mandava "me" fixo, e o servidor gravava isso como o decisor de toda decisão (RCHFLW-38).
    comment?: string;
}

/**
 * Client for the Human-in-the-Loop approval queue exposed by
 * archflow-api's {@code ApprovalController}. Mirrors the tenant-scoped
 * list + detail + submit + count surface used by the admin UI.
 */
export const approvalApi = {
    listPending: (tenantId: string) =>
        api.get<ApprovalResponse[]>(
            `/approvals/pending?tenantId=${encodeURIComponent(tenantId)}`,
        ),

    /**
     * Com `flowId` (a execução do pedido), um pedido já decidido volta com quem decidiu, quando e o
     * comentário, em vez de 404 — o link aberto depois da decisão ainda responde "quem autorizou".
     */
    get: (requestId: string, flowId?: string) =>
        api.get<ApprovalResponse>(
            `/approvals/${encodeURIComponent(requestId)}`
                + (flowId ? `?flowId=${encodeURIComponent(flowId)}` : ''),
        ),

    submit: (requestId: string, request: ApprovalSubmitRequest) =>
        api.post<ApprovalResponse>(
            `/approvals/${encodeURIComponent(requestId)}`,
            request,
        ),

    pendingCount: (tenantId: string) =>
        api.get<{ count: number }>(
            `/approvals/pending/count?tenantId=${encodeURIComponent(tenantId)}`,
        ),
};
