package br.com.archflow.api.agent.qp;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.ToolTrust;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QP — itens que o gate não resolveu")
class QpPendingItemsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QpAgentService service = new QpAgentService(null, null);

    @Test
    @DisplayName("MOSTRA é anexado à cotação preservando texto, quantidade e ranque")
    void appendsRankedCandidatesToExistingQuote() throws Exception {
        String resolverResult = """
                {"gate":"MOSTRA","confianca":0.49,"candidatos":[
                  {"skuRef":"38890:1","descricao":"FARINHA MESA CRUA 1KG MANA",
                   "score":0.49,"motivo":"catálogo: FARINHA MESA CRUA 1KG MANA"},
                  {"skuRef":"38891:1","descricao":"FARINHA DE ARROZ 1KG URBANO",
                   "score":0.41,"motivo":"catálogo: FARINHA DE ARROZ 1KG URBANO"}
                ]}
                """;
        var call = resolver(Map.of(
                "entrada", "farinha de mandioca branca crua",
                "qty", 20,
                "unidade", "kg"), resolverResult);

        String enriched = service.includePendingItems(
                "{\"items\":[{\"sku\":\"OK\"}],\"flags\":[\"PRECO_ESTIMADO\"]}",
                List.of(call), "mensagem inteira que não deve ser usada");

        JsonNode payload = mapper.readTree(enriched);
        assertThat(payload.path("items")).hasSize(1);
        assertThat(payload.path("flags")).extracting(JsonNode::asText)
                .containsExactly("PRECO_ESTIMADO", "ITENS_PENDENTES");
        JsonNode pending = payload.path("pendentes").get(0);
        assertThat(pending.path("texto").asText())
                .isEqualTo("farinha de mandioca branca crua");
        assertThat(pending.path("qty").asInt()).isEqualTo(20);
        assertThat(pending.path("unidade").asText()).isEqualTo("kg");
        assertThat(pending.path("candidatos")).extracting(c -> c.path("sku").asText())
                .containsExactly("38890:1", "38891:1");
        assertThat(pending.path("candidatos").get(0).path("confianca").asDouble())
                .isEqualTo(0.49);
    }

    @Test
    @DisplayName("LISTA sem candidato produz cotação somente com pendência")
    void createsPendingOnlyQuoteWithoutCandidates() throws Exception {
        var call = resolver(Map.of("texto", "palheta mexedor grande", "quantidade", 3),
                "{\"gate\":\"LISTA\",\"candidatos\":[]}");

        String quote = service.includePendingItems(null, List.of(call), "mensagem inteira");

        JsonNode payload = mapper.readTree(quote);
        assertThat(payload.has("items")).isFalse();
        assertThat(payload.path("flags")).extracting(JsonNode::asText)
                .containsExactly("ITENS_PENDENTES");
        assertThat(payload.path("pendentes")).hasSize(1);
        assertThat(payload.path("pendentes").get(0).path("texto").asText())
                .isEqualTo("palheta mexedor grande");
        assertThat(payload.path("pendentes").get(0).path("qty").asInt()).isEqualTo(3);
        assertThat(payload.path("pendentes").get(0).path("candidatos")).isEmpty();
    }

    @Test
    @DisplayName("RESOLVE mantém comportamento atual e não cria cotação artificial")
    void resolvedItemDoesNotCreatePendingQuote() {
        var call = resolver(Map.of("entrada", "coca 2 litros"),
                "{\"gate\":\"RESOLVE\",\"sku\":\"ABC\"}");

        assertThat(service.includePendingItems(null, List.of(call), "coca 2 litros")).isNull();
    }

    @Test
    @DisplayName("resposta sem candidatos não faz o item mencionado desaparecer")
    void malformedResolverResponseStillPreservesMentionedItem() throws Exception {
        var call = resolver(Map.of(), "resposta inesperada");

        String quote = service.includePendingItems(null, List.of(call), "torrado moído");

        JsonNode pending = mapper.readTree(quote).path("pendentes").get(0);
        assertThat(pending.path("texto").asText()).isEqualTo("torrado moído");
        assertThat(pending.path("candidatos")).isEmpty();
    }

    private McpAgentRunner.ToolCall resolver(Map<String, Object> arguments, String result) {
        return new McpAgentRunner.ToolCall(
                "resolver_sku", arguments, result, false, ToolTrust.UNTRUSTED);
    }
}
