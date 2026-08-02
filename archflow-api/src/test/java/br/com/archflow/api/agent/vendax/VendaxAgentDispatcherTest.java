package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Roteamento e contrato de volta do elo VendaX Core → ArchFlow.
 *
 * <p>Executa em linha ({@code runAndReport}) em vez de pelo executor: o que se verifica aqui é a
 * decisão, não a concorrência.</p>
 */
@DisplayName("VendaxAgentDispatcher — o que volta para o Core")
class VendaxAgentDispatcherTest {

    private final QpAgentService qp = mock(QpAgentService.class);
    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxMcpClientProvider vendax = mock(VendaxMcpClientProvider.class);
    private final CapturingSender sender = new CapturingSender();

    private final VendaxAgentDispatcher dispatcher =
            new VendaxAgentDispatcher(qp, runner, vendax, sender, mock(ExecutorService.class));

    private static VendaxInvoke invoke(String agent) {
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", agent, "msg-1",
                "quero 2 caixas de arroz", "STRONG", "intencao-pedido", "trace-1", null,
                "cliente-1", "vendedor-1", null);
    }

    static class CapturingSender extends VendaxResultSender {
        final List<VendaxResult> sent = new CopyOnWriteArrayList<>();

        CapturingSender() {
            super("http://core.test", "segredo");
        }

        @Override
        public void send(VendaxResult result) {
            sent.add(result);
        }
    }

    @Test
    @DisplayName("QP com cotação → devolve o quote")
    void qpWithQuote() {
        when(qp.quote(any(), nullable(String.class))).thenReturn(new QpAgentService.QpResult(
                "cotação pronta", List.of(), "{\"total\":1234}", "qp-abc"));

        dispatcher.runAndReport(invoke("QP"));

        assertThat(sender.sent).hasSize(1);
        VendaxResult result = sender.sent.get(0);
        assertThat(result.status()).isEqualTo(VendaxResult.OK);
        assertThat(result.richObjectType()).isEqualTo("quote");
        assertThat(result.richObject()).isEqualTo("{\"total\":1234}");
    }

    /**
     * A maioria das mensagens não vira cotação (o agente pede confirmação, ou não é pedido).
     * Mandar ERROR nesses casos encheria a conversa de aviso de falha a cada mensagem.
     */
    @Test
    @DisplayName("QP sem cotação → não devolve nada (não é erro)")
    void qpWithoutQuote() {
        when(qp.quote(any(), nullable(String.class))).thenReturn(new QpAgentService.QpResult(
                "qual embalagem?", List.of(), null, "qp-abc"));

        dispatcher.runAndReport(invoke("QP"));

        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("QP somente com pendências → entrega cotação ao Core")
    void qpWithPendingOnlyQuote() {
        String pendingQuote = """
                {"pendentes":[{"texto":"palheta mexedor grande","candidatos":[]}],
                 "flags":["ITENS_PENDENTES"]}
                """;
        when(qp.quote(any(), nullable(String.class))).thenReturn(new QpAgentService.QpResult(
                "preciso confirmar o item", List.of(), pendingQuote, "qp-abc"));

        dispatcher.runAndReport(invoke("QP"));

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).status()).isEqualTo(VendaxResult.OK);
        assertThat(sender.sent.get(0).richObjectType()).isEqualTo("quote");
        assertThat(sender.sent.get(0).richObject()).isEqualTo(pendingQuote);
    }

    @Test
    @DisplayName("agente não implementado → ERROR visível, não silêncio")
    void unknownAgent() {
        dispatcher.runAndReport(invoke("US"));

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).status()).isEqualTo(VendaxResult.ERROR);
        assertThat(sender.sent.get(0).error()).contains("US");
    }

    @Test
    @DisplayName("agente que estoura → ERROR com a causa, e o dispatcher sobrevive")
    void agentThrows() {
        when(qp.quote(any(), nullable(String.class)))
                .thenThrow(new IllegalStateException("VendaX Core fora do ar"));

        dispatcher.runAndReport(invoke("QP"));

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).status()).isEqualTo(VendaxResult.ERROR);
        assertThat(sender.sent.get(0).error()).isEqualTo("VendaX Core fora do ar");
    }

    @Test
    @DisplayName("idempotencyKey deriva do agente + mensagem de origem (reprocesso não duplica)")
    void idempotencyKey() {
        when(qp.quote(any(), nullable(String.class))).thenReturn(new QpAgentService.QpResult(
                "ok", List.of(), "{}", "qp-abc"));

        dispatcher.runAndReport(invoke("QP"));
        dispatcher.runAndReport(invoke("QP"));

        assertThat(sender.sent).hasSize(2);
        assertThat(sender.sent.get(0).idempotencyKey())
                .isEqualTo(sender.sent.get(1).idempotencyKey())
                .isEqualTo("QP:msg-1");
    }

    @Test
    @DisplayName("JSON do CS é extraído mesmo embrulhado em cerca de código")
    void extractsJsonFromFencedText() {
        String fenced = """
                Aqui está:
                ```json
                {"score": -3, "trend": "CAINDO", "tone": "peça desculpas", "bigCustomer": true}
                ```
                """;
        assertThat(VendaxAgentDispatcher.extractJson(fenced))
                .isEqualTo("{\"score\": -3, \"trend\": \"CAINDO\", "
                        + "\"tone\": \"peça desculpas\", \"bigCustomer\": true}");
        assertThat(VendaxAgentDispatcher.extractJson("sem json aqui")).isNull();
        assertThat(VendaxAgentDispatcher.extractJson(null)).isNull();
    }
}
