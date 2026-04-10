package br.com.archflow.conversation.approval;

/**
 * Interface que produtos implementam para receber notificações de
 * decisões humanas REJECTED ou EDITED.
 *
 * <p>O motor chama o callback quando uma decisão humana é recebida.
 * O produto decide o que aprender (ajustar playbook, retreinar, etc.).
 *
 * <p>O ArchFlow não interpreta as decisões — apenas notifica.
 */
public interface LearningCallback {

    /**
     * Chamado quando uma proposta é rejeitada pelo humano.
     *
     * @param tenantId  ID do tenant
     * @param requestId ID da requisição de aprovação
     * @param proposal  A proposta original que foi rejeitada
     */
    void onRejected(String tenantId, String requestId, Object proposal);

    /**
     * Chamado quando uma proposta é editada pelo humano antes de ser aprovada.
     *
     * @param tenantId  ID do tenant
     * @param requestId ID da requisição de aprovação
     * @param original  A proposta original
     * @param edited    A versão editada pelo humano
     */
    void onEdited(String tenantId, String requestId, Object original, Object edited);
}
