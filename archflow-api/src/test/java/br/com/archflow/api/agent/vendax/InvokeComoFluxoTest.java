package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O invoke que traz um fluxo é executado pelo caminho genérico.
 *
 * <h2>O que este teste protege</h2>
 *
 * <p>É a saída do {@code switch} por nome de agente ({@code ADR-025} D-1). Enquanto o roteamento
 * dependeu do nome, ir de 4 para 14 agentes significava 10 {@code case} novos e 10 deploys deste
 * runtime — para agentes cuja definição inteira já vive no catálogo do cliente.</p>
 *
 * <p>O que se afirma aqui é a <b>ausência</b> de conhecimento: o dispatcher não olha o nome do
 * agente, não sabe o que é um sentimento e tira o tipo do rich object do {@code saidaSchema} que o
 * Core declarou.</p>
 */
@DisplayName("Invoke com definição do tipo FLUXO")
class InvokeComoFluxoTest {

    private final QpAgentService qp = mock(QpAgentService.class);
    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxMcpClientProvider vendax = mock(VendaxMcpClientProvider.class);
    private final VendaxResultSender sender = mock(VendaxResultSender.class);
    private final AgentFlowRunner fluxo = mock(AgentFlowRunner.class);

    private final VendaxAgentDispatcher dispatcher = new VendaxAgentDispatcher(
            qp, runner, vendax, sender, Executors.newSingleThreadExecutor(), null, fluxo);

    private static final Map<String, Object> DOCUMENTO = Map.of(
            "id", "vendax-cs",
            "steps", List.of(Map.of("id", "avalia", "type", "mcp-agent")));

    private VendaxInvoke invoke(String agente, String saidaSchema, Map<String, Object> documento) {
        return new VendaxInvoke("1.0", "t1", "c1", agente, "m1", "chegou?", "LIGHT",
                "teste", "trace", null, "cliente", "vendedor",
                new DefinicaoDeAgente("FLUXO", documento, null, saidaSchema,
                        List.of(), Map.of(), Map.of(), "cs@2"));
    }

    private VendaxResult enviado() {
        ArgumentCaptor<VendaxResult> captor = ArgumentCaptor.forClass(VendaxResult.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("executa o documento e devolve o rich object tipado pelo saidaSchema")
    void executaODocumento() {
        when(fluxo.executar(any(), eq(DOCUMENTO), any()))
                .thenReturn(new AgentFlowRunner.Saida("{\"score\":-3,\"trend\":\"CAINDO\"}", false));

        dispatcher.runAndReport(invoke("CS", "sentiment@1", DOCUMENTO));

        VendaxResult r = enviado();
        assertThat(r.status()).isEqualTo(VendaxResult.OK);
        assertThat(r.richObjectType())
                .as("o tipo vem da definição; adivinhar aqui seria o executor decidir o que o resultado significa")
                .isEqualTo("sentiment");
        assertThat(r.richObject()).contains("CAINDO");
        verify(qp, never()).quote(any(), any());
    }

    /**
     * A propriedade que vale mais que a anterior: um agente que este runtime nunca ouviu falar roda,
     * porque o comportamento está no documento. É o que faz o 14º agente não pedir deploy.
     */
    @Test
    @DisplayName("um agente sem `case` no switch roda igual")
    void agenteDesconhecidoRoda() {
        when(fluxo.executar(any(), any(), any()))
                .thenReturn(new AgentFlowRunner.Saida("{\"ok\":true}", false));

        dispatcher.runAndReport(invoke("NS", "concessao@1", DOCUMENTO));

        VendaxResult r = enviado();
        assertThat(r.status())
                .as("pelo caminho por nome, NS responderia 'agente não implementado'")
                .isEqualTo(VendaxResult.OK);
        assertThat(r.richObjectType()).isEqualTo("concessao");
    }

    /**
     * Suspensão não é conclusão. Mandar o texto parcial ao Core viraria uma cotação incompleta na
     * conversa, e a aprovação pendente sumiria sem ninguém a ver.
     */
    @Test
    @DisplayName("fluxo suspenso em aprovação não manda resultado")
    void suspensoNaoEnvia() {
        when(fluxo.executar(any(), any(), any()))
                .thenReturn(new AgentFlowRunner.Saida("proposta parcial", true));

        dispatcher.runAndReport(invoke("CS", "sentiment@1", DOCUMENTO));

        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("FLUXO sem saidaSchema vira erro, não um rich object sem tipo")
    void semSchemaEhErro() {
        dispatcher.runAndReport(invoke("CS", null, DOCUMENTO));

        assertThat(enviado().status()).isEqualTo(VendaxResult.ERROR);
        assertThat(enviado().error()).contains("saidaSchema");
    }

    /** Definição sem fluxo — ou de uma versão anterior do Core — segue pelo caminho antigo. */
    @Test
    @DisplayName("definição sem fluxo não entra no caminho genérico")
    void semFluxoNaoEntra() {
        VendaxInvoke semFluxo = invoke("CS", "sentiment@1", Map.of());

        assertThat(semFluxo.definicao().eFluxo()).isFalse();
    }


    /**
     * A entrada é a mesma pelos dois caminhos.
     *
     * <p>Não era: o caminho por nome mandava {@code clienteRef} + conversa e o de fluxo só a
     * conversa. Isso não falha — dá <b>outra resposta</b>. Medindo a mesma conversa nos dois,
     * o sentimento caiu de {@code -7/CAINDO} para {@code 0/ESTAVEL}, e a medição que deveria
     * comparar PROMPT com FLUXO acabou comparando duas entradas diferentes.</p>
     */
    @Test
    @DisplayName("a mensagem de usuário carrega o clienteRef")
    void entradaCarregaClienteRef() {
        VendaxInvoke invoke = invoke("CS", "sentiment@1", DOCUMENTO);

        String entrada = dispatcher.entradaDoAgente(invoke);

        assertThat(entrada)
                .as("sem o identificador, obter_cliente_360 fica sem o que consultar")
                .startsWith("clienteRef=cliente")
                .contains("chegou?");
    }

    @Test
    @DisplayName("o fluxo recebe exatamente essa mensagem")
    void fluxoRecebeAMesmaEntrada() {
        when(fluxo.executar(any(), any(), any()))
                .thenReturn(new AgentFlowRunner.Saida("{\"score\":0}", false));
        VendaxInvoke invoke = invoke("CS", "sentiment@1", DOCUMENTO);

        dispatcher.runAndReport(invoke);

        ArgumentCaptor<String> entrada = ArgumentCaptor.forClass(String.class);
        verify(fluxo).executar(any(), any(), entrada.capture());
        assertThat(entrada.getValue()).isEqualTo(dispatcher.entradaDoAgente(invoke));
    }

    @Nested
    @DisplayName("tipo do rich object a partir do saidaSchema")
    class TipoDoRichObject {

        @Test
        @DisplayName("corta a versão")
        void cortaAVersao() {
            assertThat(VendaxAgentDispatcher.tipoDoRichObject("sentiment@1")).isEqualTo("sentiment");
            assertThat(VendaxAgentDispatcher.tipoDoRichObject("quote@3")).isEqualTo("quote");
        }

        @Test
        @DisplayName("schema sem versão vale como tipo")
        void semVersao() {
            assertThat(VendaxAgentDispatcher.tipoDoRichObject("quote")).isEqualTo("quote");
        }

        @Test
        @DisplayName("ausente ou vazio não vira tipo")
        void ausente() {
            assertThat(VendaxAgentDispatcher.tipoDoRichObject(null)).isNull();
            assertThat(VendaxAgentDispatcher.tipoDoRichObject("  ")).isNull();
            assertThat(VendaxAgentDispatcher.tipoDoRichObject("@1")).isNull();
        }
    }
}
