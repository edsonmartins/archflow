package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resultado devolvido ao VendaX Core (contrato CC-05). Em {@code OK}, {@code richObject} traz o
 * objeto tipado em JSON — uma cotação, um sentimento; em {@code ERROR}, {@code error} descreve a
 * falha e não há rich object (o Core transforma isso no evento {@code agent.error} que o app mostra).
 *
 * <p>{@code idempotencyKey} é o que impede a mesma cotação de virar duas mensagens quando o Core
 * reentrega ou o ArchFlow repete o envio: deriva do invoke, não do instante do envio.</p>
 */
public record VendaxResult(
        String schemaVersion,
        String tenantId,
        String conversationId,
        String agent,
        String status,
        String richObjectType,
        String richObject,
        String error,
        String idempotencyKey,

        /**
         * O que a execução gastou de modelo — aditivo e opcional, em <b>todo</b> result.
         *
         * <p>Medido em 16/09: a tabela {@code llm_usage} do Core estava vazia, porque o uso só
         * chegaria por um assunto que nada publicava, e o teto de custo por tenant (ADR-025
         * D-6/D-7) contava perguntas no lugar de dinheiro. Vindo no result, o custo chega junto da
         * execução que o produziu, inclusive quando ela falhou.</p>
         *
         * <p>Omitido quando nenhum modelo foi chamado: ausente é "nada a relatar", não "custo zero".</p>
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Uso uso) {

    /**
     * Consumo de modelo da execução.
     *
     * <p>{@code model}, {@code tokens} e {@code costCents} são a forma do PR #51 e saem sempre
     * ({@code costCents} com nulo explícito). O resto é aditivo e sai só quando se sabe.</p>
     *
     * @param model        {@code provider/modelo}; vários, separados por vírgula, se os passos de
     *                     um fluxo usaram modelos diferentes
     * @param tokens       entrada + saída; {@code null} se o provedor não informou
     * @param costCents    custo em <b>centavos de real</b>, arredondado para cima; {@code null}
     *                     quando não há preço declarado para o modelo — nulo não é "de graça"
     * @param execucaoId   a execução do agente; o Core conta cada (execução, motivo) uma vez, e é
     *                     o que torna a reentrega segura. Não é a chave de idempotência do result
     * @param provider     o provedor ({@code openrouter})
     * @param inputTokens  tokens de entrada
     * @param outputTokens tokens de saída
     * @param llmTurns     chamadas ao modelo
     * @param toolCalls    idas ao servidor de tools
     * @param durationMs   duração da execução — ou, num relato {@code APOS_PRAZO}, do trecho depois
     *                     do prazo
     */
    public record Uso(
            String model,
            Long tokens,
            Long costCents,
            @JsonInclude(JsonInclude.Include.NON_NULL) String execucaoId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long inputTokens,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long outputTokens,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer llmTurns,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer toolCalls,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long durationMs) {

        /** Compat: a forma do PR #51. */
        public Uso(String model, Long tokens, Long costCents) {
            this(model, tokens, costCents, null, null, null, null, null, null, null);
        }

        /** O consumo contado, com o custo calculado por quem tem a tabela de preços. */
        public static Uso de(br.com.archflow.api.agent.mcp.ContadorDeUso.Resumo r, Long costCents) {
            return new Uso(r.modelo(), r.tokens(), costCents, r.execucaoId(), r.provedor(),
                    r.tokensEntrada(), r.tokensSaida(), r.turnos(), r.chamadasDeTool(),
                    r.duracaoMs());
        }
    }

    /** Compat: result sem uso — a forma do contrato antes do campo aditivo. */
    public VendaxResult(String schemaVersion, String tenantId, String conversationId, String agent,
                        String status, String richObjectType, String richObject, String error,
                        String idempotencyKey) {
        this(schemaVersion, tenantId, conversationId, agent, status, richObjectType, richObject,
                error, idempotencyKey, null);
    }

    /** O mesmo result, carregando o consumo. */
    public VendaxResult comUso(Uso uso) {
        return new VendaxResult(schemaVersion, tenantId, conversationId, agent, status,
                richObjectType, richObject, error, idempotencyKey, uso);
    }

    public static final String SCHEMA_VERSION = "1.0";
    public static final String OK = "OK";
    public static final String ERROR = "ERROR";

    public static VendaxResult ok(VendaxInvoke invoke, String type, String richObject) {
        return new VendaxResult(SCHEMA_VERSION, invoke.tenantId(), invoke.conversationId(),
                invoke.agent(), OK, type, richObject, null, idempotencyKeyOf(invoke));
    }

    public static VendaxResult error(VendaxInvoke invoke, String message) {
        return new VendaxResult(SCHEMA_VERSION, invoke.tenantId(), invoke.conversationId(),
                invoke.agent(), ERROR, null, null, message, idempotencyKeyOf(invoke));
    }

    /**
     * Deriva da mensagem que originou o acionamento e do agente: dois agentes sobre a mesma
     * mensagem produzem resultados distintos, e o mesmo agente reprocessado produz o mesmo.
     */
    static String idempotencyKeyOf(VendaxInvoke invoke) {
        // A do Core vence, quando ela vem: só ele sabe que cinco mensagens de uma rajada são o
        // MESMO pedido. Aqui se enxerga um invoke isolado, e derivar da mensagem transformaria
        // cada item pedido numa cotação separada.
        if (invoke.idempotencyKey() != null && !invoke.idempotencyKey().isBlank()) {
            return invoke.idempotencyKey();
        }
        String source = invoke.sourceMessageId() != null ? invoke.sourceMessageId()
                : invoke.traceId() != null ? invoke.traceId() : invoke.conversationId();
        return invoke.agent() + ":" + source;
    }
}
