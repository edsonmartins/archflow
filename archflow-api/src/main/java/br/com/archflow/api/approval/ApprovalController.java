package br.com.archflow.api.approval;

import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;

import java.util.List;

/**
 * Controller para o protocolo Human-in-the-Loop.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /archflow/approvals/{requestId} — Submete decisão humana</li>
 *   <li>GET /archflow/approvals/pending?tenantId=X — Lista aprovações pendentes</li>
 *   <li>GET /archflow/approvals/{requestId} — Obtém detalhes de uma aprovação</li>
 * </ul>
 */
public interface ApprovalController {

    /**
     * Submete uma decisão humana para uma requisição de aprovação pendente.
     *
     * @param requestId ID da requisição de aprovação
     * @param request   Dados da decisão
     * @return Status atualizado da aprovação
     */
    ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request);

    /**
     * Lista aprovações pendentes de um tenant.
     *
     * @param tenantId ID do tenant
     * @return Lista de aprovações pendentes
     */
    List<ApprovalResponse> listPending(String tenantId);

    /**
     * Obtém detalhes de uma aprovação pelo requestId.
     *
     * @param requestId ID da requisição de aprovação
     * @return Detalhes da aprovação
     */
    ApprovalResponse getApproval(String requestId);
}
