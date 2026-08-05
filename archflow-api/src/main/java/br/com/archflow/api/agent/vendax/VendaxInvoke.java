package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ordem de acionamento de agente vinda do VendaX Core (contrato RFC-007, envelope
 * {@code core.agent.invoke}).
 *
 * <p>Campos desconhecidos são ignorados de propósito: o Core evolui o envelope (foi assim que
 * {@code customerRef}/{@code vendorRef} entraram) e uma versão nova não pode derrubar o receptor.</p>
 *
 * @param agent       QP, CS, US, ASSISTANT, TRANSCRIBER — quem o Nexus decidiu acionar
 * @param tier        LIGHT/STRONG, o tier de LLM escolhido pelo Playbook
 * @param reason      gatilho que originou o acionamento (observabilidade)
 * @param payload     JSON específico do agente (ex.: intent do ASSISTANT), opcional
 * @param customerRef cliente da conversa; nulo enquanto não há vínculo
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VendaxInvoke(
        String schemaVersion,
        String tenantId,
        String conversationId,
        String agent,
        String sourceMessageId,
        String text,
        String tier,
        String reason,
        String traceId,
        String payload,
        String customerRef,
        String vendorRef,

        /**
         * Chave que agrupa os resultados deste acionamento — <b>decidida pelo Core</b>.
         *
         * <p>Nula mantém o comportamento anterior: a chave é derivada de
         * {@code agent:sourceMessageId}, e cada mensagem produz um resultado distinto.</p>
         *
         * <p>Quando vem preenchida, quem agrupa é quem sabe agrupar. O Core conhece a conversa e a
         * janela do pedido; este runtime conhece um invoke isolado. Um cliente de distribuidora
         * pede em rajada — uma mensagem por produto —, e só o Core sabe que as cinco são o mesmo
         * pedido.</p>
         */
        String idempotencyKey,

        /** Definição resolvida pelo Core (RFC-013); nula = usar o comportamento embutido. */
        DefinicaoDeAgente definicao) {

    /**
     * Compat: invoke sem chave de agrupamento — a chave é derivada da mensagem, como sempre foi.
     *
     * <p>Existe para não obrigar todo produtor e todo teste a conhecer um campo que só a cotação
     * usa. Um record de contrato que cresce sem construtor de compatibilidade quebra chamador que
     * não tem nada a ver com a mudança.</p>
     */
    public VendaxInvoke(String schemaVersion, String tenantId, String conversationId, String agent,
                        String sourceMessageId, String text, String tier, String reason,
                        String traceId, String payload, String customerRef, String vendorRef,
                        DefinicaoDeAgente definicao) {
        this(schemaVersion, tenantId, conversationId, agent, sourceMessageId, text, tier, reason,
                traceId, payload, customerRef, vendorRef, null, definicao);
    }
}
