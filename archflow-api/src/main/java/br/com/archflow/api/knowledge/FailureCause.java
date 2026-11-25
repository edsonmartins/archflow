package br.com.archflow.api.knowledge;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Causa raiz de uma falha de persistência do conhecimento, pronta para a mensagem salva no documento.
 *
 * <p>Os stores JDBC embrulham a exceção do driver numa {@code RuntimeException} genérica, e a ingestão
 * grava só a mensagem do embrulho. Foi assim que "Failed to create pgvector index candidate" escondeu
 * por semanas que o pgjdbc recusava um {@code Instant} (RCHFLW-110): nem o documento nem o log diziam
 * por quê. Aqui se acha a exceção que de fato explica a falha, sem levar SQL junto.
 */
final class FailureCause {

    private FailureCause() {
    }

    /**
     * A exceção mais interna da cadeia. Segue também {@link SQLException#getNextException()}: o pgjdbc
     * pendura ali o erro real de um lote, e o {@link BatchUpdateException} por cima dele só repete o
     * comando.
     */
    static Throwable of(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        seen.add(current);
        while (true) {
            Throwable next = current.getCause();
            if (next == null && current instanceof SQLException sql) next = sql.getNextException();
            if (next == null || !seen.add(next)) return current;
            current = next;
        }
    }

    /**
     * Mensagem da causa raiz, no mesmo formato que a ingestão usa ({@code getMessage()} ou, sem ela,
     * {@code toString()}). A mensagem de um {@link BatchUpdateException} traz o INSERT com os valores
     * (conteúdo do documento); se ele for a raiz, sai só o nome da classe.
     *
     * <p>De um {@link SQLException} fica só a primeira linha. O pgjdbc acrescenta ao erro do servidor
     * as linhas {@code Detail}/{@code Where}/{@code Internal Query}, e o {@code Detail} de uma violação
     * de NOT NULL ou CHECK é "Failing row contains (...)" com a linha inteira, conteúdo do chunk
     * incluído. Essa mensagem vai para o documento, que a API lista ao tenant, e para o log. A
     * primeira linha é o erro em si (coluna, constraint, tipo), que é o que explica a falha.
     */
    static String describe(Throwable failure) {
        Throwable root = of(failure);
        if (root instanceof BatchUpdateException) return root.getClass().getName();
        String message = root.getMessage();
        if (message == null) return root.toString();
        if (root instanceof SQLException) {
            int quebra = message.indexOf('\n');
            if (quebra >= 0) message = message.substring(0, quebra).stripTrailing();
        }
        return message;
    }

    /** O embrulho de sempre, agora com a causa do driver na mensagem e a exceção original como causa. */
    static RuntimeException wrap(String message, Exception cause) {
        return new RuntimeException(message + ": " + describe(cause), cause);
    }
}
