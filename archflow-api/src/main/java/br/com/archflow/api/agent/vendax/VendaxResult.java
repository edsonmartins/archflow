package br.com.archflow.api.agent.vendax;

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
        String idempotencyKey) {

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
    private static String idempotencyKeyOf(VendaxInvoke invoke) {
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
