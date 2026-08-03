package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * De onde sai a resposta do passo.
 *
 * <h2>Por que isto existe</h2>
 *
 * <p>Nem todo agente responde pelo texto. Uns produzem um veredito, e o texto final <b>é</b> a
 * resposta — é o caso do CS, que devolve o sentimento em JSON. Outros orquestram: o artefato é
 * construído por uma tool e o texto do modelo é só a narração do que ele fez. É o caso do QP, cuja
 * cotação sai de {@code firmar_cotacao}.</p>
 *
 * <p>Assumir a primeira forma fez o QP parecer que não produziu nada: medido em 03/08 contra o ERP
 * real, o fluxo concluiu {@code COMPLETED} e o Core recusou com "não devolveu um JSON" — porque o
 * JSON estava no resultado da tool, não no texto.</p>
 *
 * <p>O componente <b>não sabe o que é uma cotação</b>: lê nomes de uma lista de config e devolve o
 * texto correspondente. Quem sabe o que aquilo significa é quem escreveu o fluxo.</p>
 */
@DisplayName("McpAgentComponent — saidaDaTool")
class SaidaDaToolTest {

    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final McpAgentHost host = mock(McpAgentHost.class);
    private final ExecutionContext contexto = mock(ExecutionContext.class);

    private McpAgentRunner.Result resultado(String textoFinal, McpAgentRunner.ToolCall... chamadas) {
        McpAgentRunner.Result r = mock(McpAgentRunner.Result.class);
        when(r.finalText()).thenReturn(textoFinal);
        when(r.toolCalls()).thenReturn(List.of(chamadas));
        when(r.isSuspended()).thenReturn(false);
        for (McpAgentRunner.ToolCall c : chamadas) {
            when(r.lastSuccessfulCall(c.name())).thenReturn(c);
        }
        return r;
    }

    private McpAgentRunner.ToolCall chamada(String nome, String resultText) {
        McpAgentRunner.ToolCall c = mock(McpAgentRunner.ToolCall.class);
        when(c.name()).thenReturn(nome);
        when(c.resultText()).thenReturn(resultText);
        when(c.arguments()).thenReturn(Map.of());
        when(c.isError()).thenReturn(false);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executar(Map<String, Object> config, McpAgentRunner.Result r) {
        when(contexto.get(McpAgentHost.CONTEXT_KEY)).thenReturn(Optional.of(host));
        when(contexto.getTenantId()).thenReturn("t1");
        when(host.runner()).thenReturn(runner);
        when(host.clientFor(any(), any())).thenReturn(mock(McpClient.class));
        when(host.toolCeiling(any())).thenReturn(Set.of());
        when(runner.run(any(), any(), any(), any(), any(McpAgentRunner.Options.class))).thenReturn(r);

        McpAgentComponent componente = new McpAgentComponent();
        componente.initialize(config);
        return (Map<String, Object>) componente.execute("execute", "pedido", contexto);
    }

    @Test
    @DisplayName("sem a chave, a saída é o texto do modelo — o comportamento do CS")
    void semChaveUsaOTexto() {
        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "avalie"),
                resultado("{\"score\":-7}", chamada("obter_eventos_operacionais", "[]")));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO)).isEqualTo("{\"score\":-7}");
    }

    @Test
    @DisplayName("com a chave, a saída é o resultado da tool — o comportamento do QP")
    void comChaveUsaAToolDeclarada() {
        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "cote",
                        "saidaDaTool", List.of("firmar_cotacao", "simular_cotacao")),
                resultado("Cotei os dois itens para você.",
                        chamada("resolver_sku", "{\"gate\":\"RESOLVE\"}"),
                        chamada("firmar_cotacao", "{\"type\":\"quote\",\"payload\":{}}")));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO))
                .as("a narração do modelo não é a cotação")
                .isEqualTo("{\"type\":\"quote\",\"payload\":{}}");
    }

    /** A ordem da lista é preferência de quem escreveu o fluxo: firmada vale mais que simulada. */
    @Test
    @DisplayName("a primeira tool da lista que foi chamada vence")
    void ordemEhPreferencia() {
        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "cote",
                        "saidaDaTool", List.of("firmar_cotacao", "simular_cotacao")),
                resultado("narração",
                        chamada("simular_cotacao", "{\"simulada\":true}")));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO))
                .as("firmar não foi chamada; cai para a seguinte da lista")
                .isEqualTo("{\"simulada\":true}");
    }

    /**
     * O caso que distingue "não produziu" de "produziu e eu não achei": nenhuma das tools declaradas
     * foi chamada — o agente conversou sem cotar. Devolver o texto do modelo deixa o chamador
     * decidir, em vez de esconder a diferença atrás de uma saída vazia.
     */
    @Test
    @DisplayName("nenhuma tool declarada foi chamada — devolve o texto do modelo")
    void nenhumaChamadaCaiNoTexto() {
        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "cote",
                        "saidaDaTool", List.of("firmar_cotacao", "simular_cotacao")),
                resultado("Não entendi qual produto você quer.",
                        chamada("resolver_sku", "{\"gate\":\"LISTA\"}")));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO))
                .isEqualTo("Não entendi qual produto você quer.");
    }
}
