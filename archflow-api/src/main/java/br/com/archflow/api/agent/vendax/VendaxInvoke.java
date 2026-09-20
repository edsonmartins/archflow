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
 * @param taskId      task do VendaX que originou o acionamento; nulo é o caso comum
 * @param memoria     fatos sobre o cliente que o Core decidiu mandar; vazio é o caso comum
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

        /**
         * A task do VendaX de onde partiu esta execução — <b>opaca</b> para este runtime.
         *
         * <p>Ela existe quando o vendedor abriu a conversa a partir do cockpit de tasks, e viaja
         * como contexto da sessão, nunca como conteúdo de mensagem. O ArchFlow não a interpreta,
         * não valida o formato e não decide nada com ela: apenas a repassa por header em toda
         * chamada MCP da execução (ver {@code CorrelacaoMcp.HEADER_TASK}), para que o Core possa
         * amarrar a cotação à task que a originou.</p>
         *
         * <p><b>Nula é o caso comum</b> e não é erro — a maioria das conversas não vem de task, e
         * essas execuções seguem sem vínculo. Sem vínculo é um estado legítimo; um vínculo
         * inventado credita a venda de uma conversa a uma task que não a produziu, e isso não
         * aparece em lugar nenhum: a cotação existe, o valor está certo, só o dono do crédito é
         * outro.</p>
         */
        String taskId,

        /**
         * O que se sabe sobre o cliente, <b>escolhido pelo Core</b> — um fato por item.
         *
         * <p>A memória do cliente é do Core (RFC-014 do VendaX): ele grava os fatos no Brain Sentry,
         * busca com o escopo do cliente e decide quais cabem no orçamento da skill. Este runtime não
         * fala com o Brain Sentry para isso; recebe o resultado aqui.</p>
         *
         * <p>Vem num campo próprio, e não dentro do {@code systemPrompt}, por dois motivos. Os fatos
         * foram derivados de conversas com terceiros, então entram <b>cercados</b> como conteúdo não
         * confiável — no system prompt falariam com a voz da plataforma. E mudam a cada cliente, então
         * entram no turno do usuário — no system prompt quebrariam o cache do prefixo estável.
         * Ver ADR-0005.</p>
         *
         * <p>Nulo ou vazio: o agente roda sem memória, como hoje.</p>
         */
        java.util.List<String> memoria,

        /** Definição resolvida pelo Core (RFC-013); nula = usar o comportamento embutido. */
        DefinicaoDeAgente definicao) {

    public VendaxInvoke {
        memoria = memoria == null ? java.util.List.of()
                : memoria.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * O mesmo invoke, com outro payload — para o agente que tira do payload o que não pode ir ao
     * modelo como veio (ver {@link RoteiroDeAbordagem}). Todo o resto, a chave inclusive, é o do Core.
     */
    VendaxInvoke comPayload(String novoPayload) {
        return new VendaxInvoke(schemaVersion, tenantId, conversationId, agent, sourceMessageId, text,
                tier, reason, traceId, novoPayload, customerRef, vendorRef, idempotencyKey, taskId,
                memoria, definicao);
    }

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
                traceId, payload, customerRef, vendorRef, null, null, null, definicao);
    }

    /**
     * Compat: invoke anterior à origem por task — sem vínculo, que é o caso comum.
     *
     * <p>Existe pelo mesmo motivo do construtor acima: quem só usa a chave de agrupamento não tem
     * nada a ver com atribuição de resultado, e não deve ser obrigado a declarar a ausência dela.</p>
     */
    public VendaxInvoke(String schemaVersion, String tenantId, String conversationId, String agent,
                        String sourceMessageId, String text, String tier, String reason,
                        String traceId, String payload, String customerRef, String vendorRef,
                        String idempotencyKey, DefinicaoDeAgente definicao) {
        this(schemaVersion, tenantId, conversationId, agent, sourceMessageId, text, tier, reason,
                traceId, payload, customerRef, vendorRef, idempotencyKey, null, null, definicao);
    }

    /** Compat: invoke anterior à memória enviada pelo Core. */
    public VendaxInvoke(String schemaVersion, String tenantId, String conversationId, String agent,
                        String sourceMessageId, String text, String tier, String reason,
                        String traceId, String payload, String customerRef, String vendorRef,
                        String idempotencyKey, String taskId, DefinicaoDeAgente definicao) {
        this(schemaVersion, tenantId, conversationId, agent, sourceMessageId, text, tier, reason,
                traceId, payload, customerRef, vendorRef, idempotencyKey, taskId, null, definicao);
    }
}
