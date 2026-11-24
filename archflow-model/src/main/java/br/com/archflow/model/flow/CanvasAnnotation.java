package br.com.archflow.model.flow;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Anotações do canvas — nota adesiva, quadro de grupo e separador de seção.
 *
 * <p>O designer grava as anotações em {@code steps[]} como qualquer nó (é assim que elas sobrevivem a
 * salvar, reabrir e à ida e volta do YAML), mas elas são <b>só visuais</b>: não têm componente, não
 * têm arestas e não fazem parte da execução. Antes desta regra o motor tentava resolvê-las no catálogo
 * e o fluxo inteiro falhava com {@code component not found: sticky-note}.</p>
 *
 * <p>A lista mora no modelo, como a {@link BranchCondition}, porque o documento tem mais de um leitor
 * (o deserializador do archflow-api e o runner do archflow-standalone) e cópias da regra divergiriam
 * em silêncio. O designer declara os mesmos tipos com a categoria {@code annotation} em
 * {@code archflow-ui/src/components/FlowCanvas/constants.ts}; um teste de espelho no archflow-api
 * compara as duas listas.</p>
 */
public final class CanvasAnnotation {

    /** Os tipos de nó que são anotação, em minúsculas (o {@code componentId} que o designer grava). */
    public static final Set<String> TYPES = Set.of("sticky-note", "group-frame", "section-divider");

    private CanvasAnnotation() {
    }

    /**
     * @param kind o {@code componentId} ou o {@code type} do passo; maiúsculas não importam (o designer
     *             grava {@code type} em maiúsculas, {@code STICKY-NOTE})
     */
    public static boolean isAnnotation(String kind) {
        return kind != null && TYPES.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** Um passo do documento é anotação se o {@code componentId} ou o {@code type} for de anotação. */
    public static boolean isAnnotation(Map<?, ?> step) {
        if (step == null) {
            return false;
        }
        Object componentId = step.get("componentId");
        Object type = step.get("type");
        return isAnnotation(componentId instanceof String s ? s : null)
                || isAnnotation(type instanceof String s ? s : null);
    }
}
