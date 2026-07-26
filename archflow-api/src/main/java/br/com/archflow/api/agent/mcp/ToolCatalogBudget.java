package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpModel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Mede quanto da janela de contexto o catálogo de tools consome.
 *
 * <p>O catálogo inteiro vai ao modelo em <b>todo turno</b>, e não havia nenhuma
 * contabilidade: com um punhado de tools isso é irrelevante, com dez capability
 * packs o catálogo passa a disputar espaço com o problema que o agente deveria
 * estar resolvendo — e a descoberta vinha pela fatura ou por degradação de
 * qualidade, não por um número.
 *
 * <p><b>Estimativa, não contagem.</b> Sem o tokenizador do provider não há
 * número exato; usa-se a heurística de ~4 caracteres por token sobre o texto
 * que de fato é serializado (nome + descrição + schema). Serve para ordem de
 * grandeza e tendência, que é o que a decisão "preciso enxugar os packs?"
 * precisa — não para cobrança.
 *
 * <p><b>Não descarta tools.</b> Cortar o catálogo em silêncio mudaria a
 * capacidade do agente sem ninguém pedir, e escolher <em>qual</em> cortar exige
 * a seleção semântica que o runtime ainda não tem. Aqui a política é medir e
 * avisar alto, com os maiores contribuintes nomeados para o operador saber o
 * que enxugar.
 */
final class ToolCatalogBudget {

    /** Heurística usual para texto em inglês/português; ver javadoc da classe. */
    private static final int CHARS_PER_TOKEN = 4;

    private ToolCatalogBudget() {
    }

    /** Custo estimado de uma tool, em tokens. */
    record ToolCost(String name, int estimatedTokens) {
    }

    /**
     * @param totalTokens estimativa do catálogo inteiro
     * @param perTool     custo por tool, do maior para o menor
     */
    record Estimate(int totalTokens, List<ToolCost> perTool) {

        /** Os {@code n} maiores contribuintes, para nomear no aviso. */
        String topContributors(int n) {
            return perTool.stream()
                    .limit(n)
                    .map(c -> c.name() + " (~" + c.estimatedTokens() + ")")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("nenhuma");
        }
    }

    static Estimate estimate(List<McpModel.Tool> tools) {
        List<ToolCost> costs = tools.stream()
                .map(t -> new ToolCost(t.name(), estimateTool(t)))
                .sorted(Comparator.comparingInt(ToolCost::estimatedTokens).reversed())
                .toList();
        int total = costs.stream().mapToInt(ToolCost::estimatedTokens).sum();
        return new Estimate(total, costs);
    }

    private static int estimateTool(McpModel.Tool tool) {
        int chars = length(tool.name()) + length(tool.description()) + schemaLength(tool.inputSchema());
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }

    /**
     * Aproxima o tamanho do schema serializado percorrendo chaves e valores —
     * evita depender de um ObjectMapper só para medir.
     */
    private static int schemaLength(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return 0;
        }
        return valueLength(schema);
    }

    private static int valueLength(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Map<?, ?> map) {
            int sum = 2;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sum += length(String.valueOf(e.getKey())) + 3 + valueLength(e.getValue());
            }
            return sum;
        }
        if (value instanceof Iterable<?> items) {
            int sum = 2;
            for (Object item : items) {
                sum += valueLength(item) + 1;
            }
            return sum;
        }
        return length(String.valueOf(value));
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
