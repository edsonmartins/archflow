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

        /** Definição resolvida pelo Core (RFC-013); nula = usar o comportamento embutido. */
        DefinicaoDeAgente definicao) {
}
