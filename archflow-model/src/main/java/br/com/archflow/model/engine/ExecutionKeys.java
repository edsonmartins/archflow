package br.com.archflow.model.engine;

/**
 * Chaves bem-conhecidas do {@link ExecutionContext} para passar config resolvida
 * de LLM para os adapters em runtime.
 *
 * <p>Contrato entre quem RESOLVE (runner/produto, via {@code LLMConfigResolver})
 * e quem CONSOME (adapters de chat). O engine permanece provider-agnostic: ele não
 * resolve modelo — só transporta o resultado por estas chaves.
 *
 * @since 1.0.0
 */
public final class ExecutionKeys {

    private ExecutionKeys() {
    }

    /**
     * Prefixo das chaves cujo valor é <b>infraestrutura da execução</b>, não estado: cliente
     * MCP, provider, conexão. Elas viajam no contexto para o passo alcançá-las, e
     * <b>nunca</b> entram no {@link br.com.archflow.model.flow.FlowState}.
     *
     * <p>Não é higiene: um bean vivo no estado durável quebra o checkpoint na serialização
     * — o fluxo roda e a retomada some, com um WARN no lugar de um erro. E se fosse
     * serializado, seria pior: a retomada restauraria um mapa onde o passo espera o objeto,
     * e o sintoma apareceria como "não configurado" muito longe da causa.</p>
     *
     * <p>Estado que PRECISA sobreviver ao restart — memória de conversa, passos concluídos —
     * não usa este prefixo, de propósito.</p>
     */
    public static final String TRANSIENT_PREFIX = "__archflow.transient.";

    /**
     * As variáveis que podem ser persistidas: tudo menos as de {@link #TRANSIENT_PREFIX}.
     *
     * <p>Existe como função, e não como filtro repetido em cada chamador, porque há mais de
     * um ponto que copia o contexto para o estado (checkpoint por passo e estado terminal) —
     * e um deles esquecer o filtro reintroduz a falha só no caminho menos exercitado.</p>
     */
    public static java.util.Map<String, Object> persistiveis(java.util.Map<String, Object> variaveis) {
        if (variaveis == null || variaveis.isEmpty()) {
            return new java.util.HashMap<>();
        }
        java.util.Map<String, Object> copia = new java.util.HashMap<>(variaveis.size());
        variaveis.forEach((chave, valor) -> {
            if (chave == null || !chave.startsWith(TRANSIENT_PREFIX)) {
                copia.put(chave, valor);
            }
        });
        return copia;
    }

    /**
     * Config de LLM já resolvida para o passo atual — valor do tipo
     * {@code br.com.archflow.model.config.ResolvedLLMConfig}. Quando presente,
     * adapters devem usá-la (model/temperature/maxTokens/…) em vez do default
     * estático configurado.
     */
    public static final String LLM_RESOLVED_CONFIG = "archflow.llm.resolved";

    /**
     * Override legado apenas do nome do modelo (String). Mantido para
     * compatibilidade; prefira {@link #LLM_RESOLVED_CONFIG}.
     */
    public static final String LLM_MODEL = "llm.model";

    // ── Human-in-the-loop ───────────────────────────────────────────
    //
    // Contrato entre o engine (que suspende o fluxo em AWAITING_APPROVAL e
    // grava estas chaves nas variáveis persistidas) e a camada de API (que lê
    // o estado durável para montar a fila de aprovações). Ficam aqui, em
    // archflow-model, porque os dois lados precisam do MESMO nome — enquanto
    // eram privadas do engine, a API não tinha como enxergar a fila.

    /** {@code String} — id da solicitação de aprovação pendente. Vazio = consumida. */
    public static final String APPROVAL_REQUEST_ID = "__archflow.approvalRequestId";

    /** Payload que o humano precisa avaliar, como fornecido a {@code requestApproval}. */
    public static final String APPROVAL_PROPOSAL = "__archflow.approvalProposal";

    /** {@code String} — id do step que pediu a aprovação. */
    public static final String APPROVAL_STEP_ID = "__archflow.approvalStepId";

    /** {@code String} ISO-8601 — quando a solicitação foi criada. */
    public static final String APPROVAL_REQUESTED_AT = "__archflow.approvalRequestedAt";

    /** {@code String} — descrição legível exibida ao decisor. */
    public static final String APPROVAL_DESCRIPTION = "__archflow.approvalDescription";

    /** {@code String} — título curto do pedido, exibido na fila (o que o designer chama de título). */
    public static final String APPROVAL_TITLE = "__archflow.approvalTitle";

    /**
     * {@code String} ISO-8601 ({@code PT30M}) — prazo <b>deste</b> pedido, configurado no nó. Ausente
     * ou vazio, vale o prazo global ({@code archflow.approval.timeout}).
     *
     * <p>Fica no estado durável, e não só no nó, porque quem expira é a fila, lendo o estado: ela
     * não tem a definição do fluxo, e o prazo precisa sobreviver a um restart junto com o pedido.
     * Vencido, a decisão é sempre REJECTED — o nó escolhe <i>quando</i>, nunca <i>o quê</i>.</p>
     */
    public static final String APPROVAL_TIMEOUT = "__archflow.approvalTimeout";

    /** {@code String} {@code APPROVED}|{@code REJECTED} — decisão humana registrada. */
    public static final String APPROVAL_DECISION = "__archflow.approvalDecision";

    /** {@code String} — quem decidiu. Gravado com a decisão, sob o mesmo lock. */
    public static final String APPROVAL_DECIDED_BY = "__archflow.approvalDecidedBy";

    /** {@code String} — justificativa do decisor. */
    public static final String APPROVAL_COMMENT = "__archflow.approvalComment";

    /** {@code String} ISO-8601 — quando a decisão foi submetida. */
    public static final String APPROVAL_DECIDED_AT = "__archflow.approvalDecidedAt";

    /**
     * {@code List<Map<String, Object>>} — uma entrada por decisão já tomada nesta execução
     * ({@code stepId}, {@code requestId}, {@code decision}, {@code decidedBy},
     * {@code comment}, {@code decidedAt}), na ordem em que foram tomadas.
     *
     * <p>{@code decision} é {@code APPROVED}, {@code REJECTED} ou {@code EDITED}; a edição traz
     * também {@code editedPayload}, a proposta como quem decidiu a deixou (RCHFLW-121). Já
     * {@link #APPROVAL_DECISION} continua {@code APPROVED} numa edição, para os ramos que o testam.</p>
     *
     * <p>As chaves {@code APPROVAL_DECISION}/{@code _DECIDED_BY}/{@code _COMMENT}/
     * {@code _DECIDED_AT} descrevem só o portão corrente: o pedido seguinte as limpa, para
     * que um segundo portão não herde a decisão do primeiro. É este histórico que mantém
     * "quem autorizou o primeiro portão" respondível a partir do estado durável.</p>
     */
    public static final String APPROVAL_HISTORY = "__archflow.approvalHistory";

    /**
     * Payload editado pelo humano ao aprovar, quando houver — também os dados de um formulário de
     * conversa. Numa edição da fila, o passo de aprovação devolve este mesmo valor como saída.
     */
    public static final String APPROVAL_PAYLOAD = "approvalPayload";

    /**
     * Prefixo das variáveis que <b>só o motor escreve</b>: checkpoint de passos concluídos,
     * memória de conversa, pedido e decisão de aprovação, infraestrutura transitória.
     */
    public static final String RESERVED_PREFIX = "__archflow.";

    /**
     * Se a chave pertence ao motor — o prefixo reservado e o payload editado na aprovação, que
     * ficou sem o prefixo por compatibilidade com os fluxos que já o leem.
     */
    public static boolean reservadaAoMotor(String chave) {
        return chave != null && (chave.startsWith(RESERVED_PREFIX) || APPROVAL_PAYLOAD.equals(chave));
    }

    /**
     * A entrada de uma execução sem as chaves do motor.
     *
     * <p>Quem dispara a execução não é quem a aprova. Se a entrada pudesse semear estas chaves,
     * o chamador escreveria a própria decisão ({@code approvalDecision} + {@code approvalStepId})
     * e o portão passaria sem pedido na fila, ou plantaria entradas falsas em
     * {@link #APPROVAL_HISTORY} — justamente o registro que responde "quem autorizou isto". O
     * mesmo vale para {@code completedSteps}, que pularia o portão. Uma regra só, para os pontos
     * de entrada não divergirem.</p>
     */
    public static java.util.Map<String, Object> semChavesDoMotor(java.util.Map<String, Object> entrada) {
        java.util.Map<String, Object> copia = new java.util.LinkedHashMap<>();
        if (entrada != null) {
            entrada.forEach((chave, valor) -> {
                if (!reservadaAoMotor(chave)) {
                    copia.put(chave, valor);
                }
            });
        }
        return copia;
    }

    /**
     * Se o estado espera uma decisão humana ainda não tomada: AWAITING_APPROVAL com o pedido
     * não consumido. Um estado assim só segue por {@code submitApproval} — retomá-lo por outro
     * caminho concluiria o fluxo sem ninguém ter decidido, e o estado durável não saberia dizer
     * quem autorizou.
     */
    public static boolean aguardaDecisaoHumana(br.com.archflow.model.flow.FlowState estado) {
        if (estado == null || estado.getStatus() != br.com.archflow.model.flow.FlowStatus.AWAITING_APPROVAL) {
            return false;
        }
        Object pedido = estado.getVariables() != null ? estado.getVariables().get(APPROVAL_REQUEST_ID) : null;
        return pedido != null && !String.valueOf(pedido).isBlank();
    }
}
