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

    /**
     * Como {@link #getApproval(String)}, mas também encontra um pedido já decidido quando se sabe
     * de qual execução ele é — e então devolve quem decidiu, quando e o comentário.
     *
     * @param flowId execução que emitiu o pedido (opcional)
     */
    default ApprovalResponse getApproval(String requestId, String flowId) {
        return getApproval(requestId);
    }

    /**
     * Detalhe restrito a um tenant (RCHFLW-39): um pedido de outro tenant é "não encontrado".
     * Sem implementação padrão de propósito — uma que ignorasse o recorte reabriria o vazamento em
     * silêncio em qualquer implementação nova.
     *
     * @param tenantScope tenant que o pedido precisa ter; {@code null} aceita qualquer um
     */
    ApprovalResponse getApproval(String requestId, String flowId, String tenantScope);

    /**
     * Decisão restrita a um tenant (RCHFLW-39): um pedido de outro tenant é "não encontrado" e nada
     * é decidido.
     *
     * @param tenantScope tenant que o pedido precisa ter; {@code null} aceita qualquer um
     */
    ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request, String tenantScope);
}
