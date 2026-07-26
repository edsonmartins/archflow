package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCatalogBudget")
class ToolCatalogBudgetTest {

    private static McpModel.Tool tool(String name, String description, Map<String, Object> schema) {
        return new McpModel.Tool(name, description, schema);
    }

    private static final Map<String, Object> TRIVIAL =
            Map.of("type", "object", "properties", Map.of());

    @Test
    @DisplayName("soma o custo de todas as tools")
    void sumsAcrossTools() {
        var estimate = ToolCatalogBudget.estimate(List.of(
                tool("a", "descrição curta", TRIVIAL),
                tool("b", "descrição curta", TRIVIAL)));

        assertThat(estimate.totalTokens()).isPositive();
        assertThat(estimate.perTool()).hasSize(2);
    }

    @Test
    @DisplayName("ordena do maior para o menor — o aviso precisa nomear quem pesa")
    void ordersByCostDescending() {
        var estimate = ToolCatalogBudget.estimate(List.of(
                tool("barata", "x", TRIVIAL),
                tool("cara", "descrição bem mais longa ".repeat(20), Map.of(
                        "type", "object",
                        "required", List.of("a", "b", "c"),
                        "properties", Map.of(
                                "a", Map.of("type", "string", "description", "campo a"),
                                "b", Map.of("type", "integer", "description", "campo b"),
                                "c", Map.of("type", "string", "description", "campo c"))))));

        assertThat(estimate.perTool()).first()
                .satisfies(c -> assertThat(c.name()).isEqualTo("cara"));
        assertThat(estimate.topContributors(1)).startsWith("cara (~");
    }

    @Test
    @DisplayName("o schema entra na conta — é ele que cresce com capability packs")
    void schemaContributesToTheCost() {
        var semSchema = ToolCatalogBudget.estimate(List.of(tool("t", "d", TRIVIAL)));
        var comSchema = ToolCatalogBudget.estimate(List.of(tool("t", "d", Map.of(
                "type", "object",
                "properties", Map.of(
                        "campo_com_nome_longo", Map.of(
                                "type", "string",
                                "description", "uma descrição de parâmetro razoavelmente detalhada"))))));

        assertThat(comSchema.totalTokens()).isGreaterThan(semSchema.totalTokens());
    }

    @Test
    @DisplayName("catálogo vazio custa zero")
    void emptyCatalog() {
        var estimate = ToolCatalogBudget.estimate(List.of());

        assertThat(estimate.totalTokens()).isZero();
        assertThat(estimate.topContributors(3)).isEqualTo("nenhuma");
    }

    @Test
    @DisplayName("dez packs custam ordem de grandeza mais que um")
    void scalesWithPackCount() {
        List<McpModel.Tool> umPack = List.copyOf(pack(1));
        List<McpModel.Tool> dezPacks = List.copyOf(pack(10));

        int um = ToolCatalogBudget.estimate(umPack).totalTokens();
        int dez = ToolCatalogBudget.estimate(dezPacks).totalTokens();

        assertThat(dez).isGreaterThan(um * 8);
    }

    /** {@code n} packs de 8 tools, como o cenário de capability packs. */
    private static List<McpModel.Tool> pack(int packs) {
        return java.util.stream.IntStream.range(0, packs * 8)
                .mapToObj(i -> tool("tool_" + i,
                        "faz alguma coisa razoavelmente descrita para o modelo entender",
                        Map.of("type", "object", "properties", Map.of(
                                "alvo", Map.of("type", "string", "description", "o alvo da operação")))))
                .toList();
    }
}
