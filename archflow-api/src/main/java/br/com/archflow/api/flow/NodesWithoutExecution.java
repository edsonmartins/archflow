package br.com.archflow.api.flow;

import java.util.Locale;
import java.util.Set;

/**
 * Os tipos de nó que a paleta do designer oferece e que o motor ainda não executa (RCHFLW-11).
 *
 * <p><b>Por que uma lista, e não só a pergunta ao factory.</b> O {@link NodeSupport} responde "este
 * documento roda?" construindo cada nó; o designer precisa da resposta <i>antes</i> de existir
 * documento — para marcar a paleta e o nó, e para não deixar o Executar começar. Sem a lista, o
 * usuário só descobria ao executar, com um {@code component not found: agent} que não diz nada.</p>
 *
 * <p>O designer declara os mesmos tipos em {@code NOT_EXECUTABLE_NODE_TYPES}
 * ({@code archflow-ui/src/components/FlowCanvas/constants.ts}); o {@code NodesWithoutExecutionMirrorTest}
 * compara as duas listas e confere, pelo factory de verdade, que cada tipo daqui realmente não executa
 * e que os outros tipos da paleta executam. Quando um destes ganhar execução, ele sai daqui e de lá.</p>
 *
 * <p><b>A lista decide sozinha, sem o catálogo.</b> O designer não sabe quais plugins o servidor
 * carregou, então o motor também não abre exceção para um componente que use um destes ids: os dois
 * lados dão a mesma resposta para o mesmo nó. Estes tipos são ids reservados para um plugin.</p>
 *
 * <p>Entrada e Saída não estão aqui: executam como passagem ({@link PassThroughStep}). O
 * {@code llm-streaming} também não: roda como o {@code llm-chat} (RCHFLW-57).</p>
 */
public final class NodesWithoutExecution {

    /** Os tipos, em minúsculas (o {@code componentId} que o designer grava). */
    public static final Set<String> TYPES = Set.of(
            "agent", "assistant",
            "parallel", "loop", "subflow",
            "transform", "map", "filter", "reduce", "merge",
            "tool", "function", "custom", "mcp-tool",
            "prompt-template", "prompt-chunk",
            "skills", "linktor-send", "linktor-escalate");

    private NodesWithoutExecution() {
    }

    /** @param kind o {@code componentId} do nó, ou o {@code type} quando não há um; caixa não importa */
    public static boolean contains(String kind) {
        return kind != null && TYPES.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** A mensagem que a execução devolve — a mesma no passo e na validação do início da execução. */
    public static String message(String kind) {
        return "o tipo de nó '" + kind + "' ainda não tem execução no servidor";
    }
}
