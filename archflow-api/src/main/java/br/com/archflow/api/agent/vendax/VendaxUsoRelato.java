package br.com.archflow.api.agent.vendax;

import java.util.Objects;

/**
 * Consumo de uma execução que <b>não</b> vai num result — enviado ao Core em
 * {@code POST /api/v1/public/archflow/uso}, com a mesma assinatura do result.
 *
 * <h2>Por que existe</h2>
 *
 * <p>O teto de custo por tenant só funciona se todo consumo chegar ao Core, e se chegar uma vez.
 * O {@link VendaxResult#uso()} cobre quem manda result. Sobram três casos, cada um um
 * {@link Motivo}: a execução que termina sem result, a que suspende esperando aprovação, e o que o
 * laço gasta depois de um {@code ERROR} por prazo.</p>
 *
 * <p>O uso do result segue no próprio result (motivo implícito {@code RESULT}). Esta rota não o
 * repete: o Core recusa {@code motivo=RESULT} aqui.</p>
 *
 * @param uso sempre com {@code execucaoId} — é ele, com o motivo, que torna a reentrega segura
 */
public record VendaxUsoRelato(
        String schemaVersion,
        String tenantId,
        String agent,
        String conversationId,
        String idempotencyKey,
        Motivo motivo,
        VendaxResult.Uso uso) {

    public enum Motivo {
        /** A execução terminou e não gerou result (QP sem cotação). */
        SEM_RESULT,
        /** O fluxo parou esperando aprovação; relata o que gastou até ali. */
        SUSPENSO,
        /** O laço continuou depois do ERROR por prazo; relata só o que gastou depois dele. */
        APOS_PRAZO
    }

    public VendaxUsoRelato {
        Objects.requireNonNull(motivo, "motivo");
        Objects.requireNonNull(uso, "uso");
        Objects.requireNonNull(uso.execucaoId(), "uso.execucaoId: sem ele o Core não protege contra contagem dupla");
    }

    /** A mesma chave que o result desta execução teria — o Core casa os dois por ela. */
    public static VendaxUsoRelato de(VendaxInvoke invoke, Motivo motivo, VendaxResult.Uso uso) {
        return new VendaxUsoRelato(VendaxResult.SCHEMA_VERSION, invoke.tenantId(), invoke.agent(),
                invoke.conversationId(), VendaxResult.idempotencyKeyOf(invoke), motivo, uso);
    }
}
