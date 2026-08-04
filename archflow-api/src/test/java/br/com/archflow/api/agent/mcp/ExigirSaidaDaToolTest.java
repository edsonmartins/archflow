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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quando o passo diz que produz um artefato, terminar falando não é conclusão.
 *
 * <h2>O que motivou</h2>
 *
 * <p>Medido no VendaX em 03–04/08, contra um pedido real: de <b>sete</b> execuções de um agente de
 * cotação, <b>três</b> terminaram sem chamar a tool que monta o artefato. Ele resolvia os cinco
 * produtos certos, um por um, e concluía narrando o que fez. O trabalho inteiro — várias chamadas
 * corretas — era descartado, e quem chamou recebia um texto onde esperava um JSON.</p>
 *
 * <p>O prompt já mandava, em maiúsculas. Prompt não tem compilador: o que garante um invariante do
 * fluxo é o fluxo, não a redação.</p>
 *
 * <p>{@code exigirSaidaDaTool} é opcional e desligado por padrão — um agente que responde pelo
 * texto (o CS devolve o sentimento em JSON no próprio texto) continua funcionando sem tocar nada.</p>
 */
@DisplayName("McpAgentComponent — exigirSaidaDaTool")
class ExigirSaidaDaToolTest {

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

    /** O caso real: cinco resoluções certas e nenhuma cotação montada. */
    @Test
    @DisplayName("exigido e não chamado: o passo falha, com o texto do modelo na mensagem")
    void exigidoENaoChamadoFalha() {
        Map<String, Object> config = Map.of(
                "systemPrompt", "cote",
                "saidaDaTool", List.of("montar_cotacao"),
                "exigirSaidaDaTool", true);

        assertThatThrownBy(() -> executar(config,
                resultado("Resolvi os cinco produtos: carne seca, presunto, calabresa…",
                        chamada("resolver_sku", "{\"gate\":\"MOSTRA\"}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("montar_cotacao")
                .as("o texto do modelo é a pista de por que ele não chamou")
                .hasMessageContaining("Resolvi os cinco produtos");
    }

    @Test
    @DisplayName("exigido e chamado: passa, e a saída é o resultado da tool")
    void exigidoEChamadoPassa() {
        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "cote",
                        "saidaDaTool", List.of("montar_cotacao"),
                        "exigirSaidaDaTool", true),
                resultado("Montei a cotação.",
                        chamada("resolver_sku", "{\"gate\":\"RESOLVE\"}"),
                        chamada("montar_cotacao", "{\"type\":\"quote\",\"payload\":{}}")));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO))
                .isEqualTo("{\"type\":\"quote\",\"payload\":{}}");
    }

    /**
     * Sem a flag, o comportamento é o de antes: o texto do modelo vale como resposta. É o que
     * mantém funcionando o agente que responde falando — e é o padrão.
     */
    @Test
    @DisplayName("sem a flag, nada muda: o texto do modelo continua sendo a saída")
    void semAFlagNadaMuda() {
        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "cote", "saidaDaTool", List.of("montar_cotacao")),
                resultado("Não consegui montar.",
                        chamada("resolver_sku", "{\"gate\":\"LISTA\"}")));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO)).isEqualTo("Não consegui montar.");
    }

    /**
     * Suspensão é aguardo humano, não conclusão. Falhar aqui descartaria a aprovação pendente —
     * e o passo teria de recomeçar do zero depois de alguém já ter decidido.
     */
    @Test
    @DisplayName("laço suspenso não falha, mesmo com a exigência ligada")
    void suspensoNaoFalha() {
        McpAgentRunner.Result r = mock(McpAgentRunner.Result.class);
        when(r.finalText()).thenReturn(null);
        when(r.toolCalls()).thenReturn(List.of());
        when(r.isSuspended()).thenReturn(true);
        br.com.archflow.api.agent.mcp.McpAgentState.PendingApproval pa =
                mock(br.com.archflow.api.agent.mcp.McpAgentState.PendingApproval.class);
        when(pa.requestId()).thenReturn("req-1");
        when(pa.toolName()).thenReturn("firmar_cotacao");
        when(r.pendingApproval()).thenReturn(pa);

        Map<String, Object> saida = executar(
                Map.of("systemPrompt", "cote",
                        "saidaDaTool", List.of("montar_cotacao"),
                        "exigirSaidaDaTool", true), r);

        assertThat(saida.get(McpAgentComponent.SAIDA_SUSPENSO)).isEqualTo(true);
    }

    /** A exigência chega ao runner — é ela que faz a cobrança de um turno acontecer. */
    @Test
    @DisplayName("a lista exigida é repassada ao runner")
    void repassaAoRunner() {
        executar(Map.of("systemPrompt", "cote",
                        "saidaDaTool", List.of("montar_cotacao"),
                        "exigirSaidaDaTool", true),
                resultado("ok", chamada("montar_cotacao", "{}")));

        org.mockito.ArgumentCaptor<McpAgentRunner.Options> captor =
                org.mockito.ArgumentCaptor.forClass(McpAgentRunner.Options.class);
        org.mockito.Mockito.verify(runner).run(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().requiredOutputTools()).containsExactly("montar_cotacao");
    }

    /** Sem a flag, o runner não cobra nada — a lista vai vazia. */
    @Test
    @DisplayName("sem a flag, o runner não recebe exigência")
    void semAFlagRunnerNaoCobra() {
        executar(Map.of("systemPrompt", "cote", "saidaDaTool", List.of("montar_cotacao")),
                resultado("ok"));

        org.mockito.ArgumentCaptor<McpAgentRunner.Options> captor =
                org.mockito.ArgumentCaptor.forClass(McpAgentRunner.Options.class);
        org.mockito.Mockito.verify(runner).run(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().requiredOutputTools()).isEmpty();
    }
}
