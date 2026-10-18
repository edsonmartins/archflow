package br.com.archflow.model.flow;

import java.util.Locale;

/**
 * A condição que um <b>ramo</b> declarado numa aresta ({@code branch}) quer dizer.
 *
 * <p>Uma aresta pode, em vez de trazer a condição escrita à mão, declarar o ramo do nó de origem que
 * ela segue: {@code AUTO}/{@code REVIEW}/{@code ESCALATE} de um {@code decision}, {@code true}/
 * {@code false} de um {@code condition}, um caso de um {@code switch}. Qual variável cada tipo de nó
 * grava é regra do motor — e ela mora <b>aqui</b>, no modelo, porque o documento tem três leitores
 * (a factory do archflow-api, o transporte do archflow-standalone e a DSL) e dois deles precisam
 * convertê-la. Duas cópias da regra divergiriam em silêncio.</p>
 *
 * <p>Uma {@code condition} escrita à mão na mesma aresta <b>vence</b> o ramo: quem a escreveu sabia o
 * que queria.</p>
 */
public final class BranchCondition {

    private BranchCondition() {
    }

    /**
     * @param stepId o id do nó de origem
     * @param kind   o tipo do nó de origem — o {@code componentId}, ou o {@code type} quando o
     *               designer não grava um; maiúsculas não importam
     * @param branch o ramo declarado na aresta
     * @return a condição, ou {@code null} quando não há ramo ou o nó de origem não tem ramos
     */
    public static String of(String stepId, String kind, String branch) {
        if (branch == null || branch.isBlank() || kind == null) {
            return null;
        }
        String ramo = branch.trim().replace("'", "");
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "condition", "switch" -> "${" + stepId + ".branch} == '" + ramo + "'";
            case "decision" -> "${" + stepId + ".route} == '" + ramo + "'";
            default -> null;
        };
    }
}
