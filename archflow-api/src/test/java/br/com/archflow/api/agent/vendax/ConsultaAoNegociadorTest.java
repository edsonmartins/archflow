package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.api.agent.mcp.ToolTrust;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A consulta do vendedor ao NS, de ponta a ponta no dispatcher (VendaX, ADR-038 D-3).
 *
 * <p>O que se protege aqui é o contrato que o Core confere: o {@code parametro} com que ele refaz
 * a conta, a mesma chave de idempotência no erro (é ela que faz a tela parar de esperar), o prazo
 * da classe interativa, e o {@code uso} que alimenta o teto de custo.</p>
 */
@DisplayName("NS — consulta do vendedor em linguagem")
class ConsultaAoNegociadorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAVE = "ns-consulta:0b7e";

    private final QpAgentService qp = mock(QpAgentService.class);
    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxMcpClientProvider vendax = mock(VendaxMcpClientProvider.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();
    private final VendaxAgentDispatcher dispatcher =
            new VendaxAgentDispatcher(qp, runner, vendax, sender, mock(ExecutorService.class));

    private static VendaxInvoke consulta(String pergunta) {
        return consulta(pergunta, VendaxAgentDispatcher.NS_CONSULTA);
    }

    private static VendaxInvoke consulta(String pergunta, String reason) {
        String payload = "{\"consultaId\":\"0b7e\",\"pergunta\":\"" + pergunta + "\","
                + "\"clienteRef\":\"cli-1\",\"skuRef\":\"sku-9\",\"precoUnitarioCents\":4590,"
                + "\"quantidadeCaixas\":30,\"respostaPorRegra\":\"sem regra\"}";
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", "NS", null, pergunta, "LIGHT",
                reason, "trace-ns", payload, "cli-1", "vend-7", CHAVE, null, null);
    }

    /** Uma chamada bem-sucedida de plano_negociacao com estes argumentos. */
    private static McpAgentRunner.ToolCall plano(Integer descontoPb, int caixas) {
        Map<String, Object> item = new HashMap<>();
        item.put("skuRef", "sku-9");
        item.put("precoUnitarioCents", 4590);
        item.put("quantidadeCaixas", caixas);
        item.put("descontoPedidoPb", descontoPb);
        return new McpAgentRunner.ToolCall("plano_negociacao",
                Map.of("clienteRef", "cli-1", "vendedorRef", "vend-7", "itens", List.of(item)),
                "{\"tetoSemEscalar\":600}", false, ToolTrust.UNTRUSTED);
    }

    private void runnerResponde(McpAgentRunner.Result resultado) {
        when(runner.run(anyString(), anyString(), anyString(), any(),
                any(McpAgentRunner.Options.class))).thenReturn(resultado);
    }

    private McpAgentRunner.Options opcoesUsadas() {
        ArgumentCaptor<McpAgentRunner.Options> captor =
                ArgumentCaptor.forClass(McpAgentRunner.Options.class);
        verify(runner).run(anyString(), anyString(), anyString(), any(), captor.capture());
        return captor.getValue();
    }

    private VendaxResult unico() {
        assertThat(sender.sent).hasSize(1);
        return sender.sent.get(0);
    }

    private static JsonNode rich(VendaxResult r) throws Exception {
        return MAPPER.readTree(r.richObject());
    }

    @Nested
    @DisplayName("o parâmetro que o Core refaz")
    class Parametro {

        @Test
        @DisplayName("\"faz 8 e meio?\": descontoPedidoPb=850, sem volume")
        void descontoFracionario() throws Exception {
            runnerResponde(new McpAgentRunner.Result(
                    "{\"parametro\":{\"descontoPedidoPb\":850,\"caixas\":null},"
                            + "\"resposta\":\"passa do teto do seu passo, que é 6.\"}",
                    List.of(plano(850, 30))));

            dispatcher.runAndReport(consulta("faz 8 e meio?"));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(r.richObjectType()).isEqualTo("ns_consulta");
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);
            JsonNode rich = rich(r);
            assertThat(rich.path("parametro").path("descontoPedidoPb").asLong()).isEqualTo(850);
            assertThat(rich.path("parametro").path("caixas").isNull())
                    .as("a quantidade do pedido foi à tool, mas não fazia parte da pergunta — "
                            + "devolvê-la geraria um eco que ninguém pediu")
                    .isTrue();
            assertThat(rich.path("resposta").asText()).isEqualTo("passa do teto do seu passo, que é 6.");
        }

        /**
         * O dito contra o feito: o modelo relata 800 e chamou a tool com 850. Os números da frase
         * saíram da chamada com 850; devolver 800 faria o Core refazer a conta errada.
         */
        @Test
        @DisplayName("vale o desconto que foi à tool, não o que o modelo diz ter usado")
        void chamadaVenceAfirmacao() throws Exception {
            runnerResponde(new McpAgentRunner.Result(
                    "{\"parametro\":{\"descontoPedidoPb\":800,\"caixas\":null},\"resposta\":\"x\"}",
                    List.of(plano(850, 30))));

            dispatcher.runAndReport(consulta("faz 8 e meio?"));

            assertThat(rich(unico()).path("parametro").path("descontoPedidoPb").asLong())
                    .isEqualTo(850);
        }

        @Test
        @DisplayName("\"dá pra melhorar se levar 40?\": volume da chamada, sem desconto")
        void volumePerguntado() throws Exception {
            runnerResponde(new McpAgentRunner.Result(
                    "{\"parametro\":{\"descontoPedidoPb\":null,\"caixas\":40},"
                            + "\"resposta\":\"com 40 caixas o pedido já cumpre as 30 da faixa.\"}",
                    List.of(plano(null, 40))));

            dispatcher.runAndReport(consulta("dá pra fazer um preço melhor se levar 40?"));

            JsonNode parametro = rich(unico()).path("parametro");
            assertThat(parametro.path("caixas").asInt()).isEqualTo(40);
            assertThat(parametro.path("descontoPedidoPb").isNull()).isTrue();
        }

        /** O modelo disse que o volume era parte da pergunta, com a mesma quantidade do pedido. */
        @Test
        @DisplayName("volume declarado igual ao do pedido também entra")
        void volumeDeclaradoIgualAoPedido() throws Exception {
            runnerResponde(new McpAgentRunner.Result(
                    "{\"parametro\":{\"descontoPedidoPb\":null,\"caixas\":30},\"resposta\":\"x\"}",
                    List.of(plano(null, 30))));

            dispatcher.runAndReport(consulta("e com essas 30?"));

            assertThat(rich(unico()).path("parametro").path("caixas").asInt()).isEqualTo(30);
        }

        /** Sem chamada, todo número da frase foi inventado: parâmetro vazio, e a regra fica. */
        @Test
        @DisplayName("não entendeu: OK com parâmetro vazio")
        void naoEntendeu() throws Exception {
            runnerResponde(new McpAgentRunner.Result(
                    "{\"parametro\":{\"descontoPedidoPb\":500},\"resposta\":\"dá sim, 5%\"}",
                    List.of()));

            dispatcher.runAndReport(consulta("e aquele lance?"));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(rich(r).path("parametro").isEmpty())
                    .as("o modelo afirmou um parâmetro sem consultar o plano — nada a refazer")
                    .isTrue();
        }

        @Test
        @DisplayName("consultou e não redigiu: ERROR, para a tela parar de esperar")
        void semFrase() {
            runnerResponde(new McpAgentRunner.Result(
                    "{\"parametro\":{\"descontoPedidoPb\":800},\"resposta\":\"\"}",
                    List.of(plano(800, 30))));

            dispatcher.runAndReport(consulta("faz 8?"));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);
        }

        /** Descartar uma frase boa pela embalagem só deixaria a tela com a regra. */
        @Test
        @DisplayName("frase sem JSON em volta ainda vale como resposta")
        void textoCru() throws Exception {
            runnerResponde(new McpAgentRunner.Result(
                    "passa do teto do seu passo.", List.of(plano(800, 30))));

            dispatcher.runAndReport(consulta("faz 8?"));

            JsonNode rich = rich(unico());
            assertThat(rich.path("resposta").asText()).isEqualTo("passa do teto do seu passo.");
            assertThat(rich.path("parametro").path("descontoPedidoPb").asLong()).isEqualTo(800);
        }
    }

    @Nested
    @DisplayName("como o laço é montado")
    class Montagem {

        @Test
        @DisplayName("só plano_negociacao, tier do invoke, laço curto e -32001 terminal")
        void opcoes() {
            runnerResponde(new McpAgentRunner.Result("{}", List.of()));

            dispatcher.runAndReport(consulta("faz 8?"));

            McpAgentRunner.Options o = opcoesUsadas();
            assertThat(o.access().isAllowed("plano_negociacao")).isTrue();
            assertThat(o.access().isAllowed("firmar_cotacao"))
                    .as("consulta não grava nem promete")
                    .isFalse();
            assertThat(o.tier()).isEqualTo("LIGHT");
            assertThat(o.maxIterations()).isLessThanOrEqualTo(4);
            assertThat(o.codigosQueEncerram()).containsExactly(-32001);
            assertThat(o.uso()).as("o consumo é contado desde o primeiro turno").isNotNull();
        }

        /** Sem os campos do payload, o modelo teria de inventar SKU, preço e quantidade. */
        @Test
        @DisplayName("o payload da consulta chega ao modelo")
        void payloadNaEntrada() {
            runnerResponde(new McpAgentRunner.Result("{}", List.of()));

            dispatcher.runAndReport(consulta("faz 8?"));

            ArgumentCaptor<String> entrada = ArgumentCaptor.forClass(String.class);
            verify(runner).run(anyString(), anyString(), entrada.capture(), any(),
                    any(McpAgentRunner.Options.class));
            assertThat(entrada.getValue())
                    .contains("entrada=faz 8?")
                    .contains("payload=")
                    .contains("\"skuRef\":\"sku-9\"")
                    .contains("\"precoUnitarioCents\":4590")
                    .contains("\"quantidadeCaixas\":30");
        }

        /** O CS manda só a janela; a entrada dele fica exatamente como era. */
        @Test
        @DisplayName("payload só com a janela não ganha linha nova")
        void janelaSozinhaNaoMuda() {
            VendaxInvoke cs = new VendaxInvoke("1.0", "t1", "c1", "CS", "m1", "chegou?", "LIGHT",
                    "teste", "trace", "{\"messages\":[{\"direction\":\"INBOUND\",\"text\":\"oi\"}]}",
                    "cliente", "vendedor", null);

            assertThat(dispatcher.entradaDoAgente(cs)).doesNotContain("payload=");
        }

        /**
         * A armadilha de thread, pela terceira vez. O prazo exige que o laço rode noutra thread, e
         * o ThreadLocal da correlação não atravessa: sem repor lá, {@code X-Vendax-Cliente} e
         * {@code X-Vendax-Vendedor} não sairiam — e nada falharia.
         */
        @Test
        @DisplayName("a correlação está na thread que chama as tools")
        void correlacaoNaThreadDoLaco() {
            AtomicReference<CorrelacaoMcp.Dados> durante = new AtomicReference<>();
            AtomicReference<Thread> onde = new AtomicReference<>();
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class))).thenAnswer(chamada -> {
                        durante.set(CorrelacaoMcp.atual());
                        onde.set(Thread.currentThread());
                        return new McpAgentRunner.Result("{}", List.of());
                    });

            dispatcher.runAndReport(consulta("faz 8?"));

            assertThat(onde.get()).isNotSameAs(Thread.currentThread());
            assertThat(durante.get().clienteRef())
                    .as("sem isto o header não sai, e o Core volta a confiar no argumento do modelo")
                    .isEqualTo("cli-1");
            assertThat(durante.get().vendedorRef()).isEqualTo("vend-7");
            assertThat(durante.get().janelaChave()).isEqualTo(CHAVE);
            assertThat(durante.get().traceId()).isEqualTo("trace-ns");
        }

        @Test
        @DisplayName("NS com outro reason não é executado aqui")
        void outroReason() {
            dispatcher.runAndReport(consulta("faz 8?", "ns:negociacao"));

            assertThat(unico().status()).isEqualTo(VendaxResult.ERROR);
            verify(runner, never()).run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class));
        }
    }

    @Nested
    @DisplayName("classe interativa")
    class Interativa {

        @Test
        @DisplayName("-32001: ERROR na hora, com a mesma chave")
        void naoContratado() {
            McpAgentRunner.ToolCall recusada = new McpAgentRunner.ToolCall("plano_negociacao",
                    Map.of(), "ERRO: MCP RPC error -32001: o agente NS não foi contratado",
                    true, ToolTrust.TRUSTED, -32001);
            runnerResponde(new McpAgentRunner.Result("", List.of(recusada), null, recusada));

            dispatcher.runAndReport(consulta("faz 8?"));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("não foi contratado");
            assertThat(r.idempotencyKey())
                    .as("é a chave que faz a tela parar de esperar")
                    .isEqualTo(CHAVE);
        }

        /**
         * O vendedor está olhando para a tela. Passado o prazo, o ERROR sai na hora, e o que o laço
         * ainda produzir é descartado — um OK atrasado seria uma segunda resposta para a mesma chave.
         */
        @Test
        @DisplayName("passou do prazo: ERROR na hora, e nada chega depois")
        void prazo() throws Exception {
            dispatcher.setPrazoInterativo(Duration.ofMillis(200));
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class))).thenAnswer(chamada -> {
                        try {
                            Thread.sleep(1_500);
                        } catch (InterruptedException e) {
                            // interrompido pelo prazo: devolve algo mesmo assim, para provar
                            // que o resultado tardio é descartado
                        }
                        return new McpAgentRunner.Result(
                                "{\"parametro\":{},\"resposta\":\"tarde\"}", List.of());
                    });

            long inicio = System.nanoTime();
            dispatcher.runAndReport(consulta("faz 8?"));
            long ms = (System.nanoTime() - inicio) / 1_000_000;

            assertThat(ms).as("não reenfileira nem espera o laço").isLessThan(1_200);
            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("não respondeu");
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);

            Thread.sleep(300);
            assertThat(sender.sent).as("o resultado tardio não vira segunda resposta").hasSize(1);
            assertThat(CorrelacaoMcp.atual().clienteRef())
                    .as("a thread do executor é reusada pelo próximo invoke")
                    .isNull();
        }

        @Test
        @DisplayName("falha do laço vira ERROR com o motivo, e não exceção perdida")
        void falhaDoLaco() {
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class)))
                    .thenThrow(new IllegalStateException("Falha ao listar tools do MCP server"));

            dispatcher.runAndReport(consulta("faz 8?"));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.error()).contains("listar tools");
            assertThat(r.idempotencyKey()).isEqualTo(CHAVE);
        }
    }

    @Nested
    @DisplayName("uso no result")
    class Uso {

        private void runnerGasta(McpAgentRunner.Result resultado) {
            when(runner.run(anyString(), anyString(), anyString(), any(),
                    any(McpAgentRunner.Options.class))).thenAnswer(chamada -> {
                        McpAgentRunner.Options o = chamada.getArgument(4);
                        o.uso().somar("openrouter/google/gemini-2.5-flash-lite", 750, 62);
                        return resultado;
                    });
        }

        @Test
        @DisplayName("modelo, tokens e custo seguem no result")
        void comPreco() {
            dispatcher.setPrecos(new TabelaDePrecos(Map.of("openrouter/google/gemini-2.5-flash-lite",
                    new TabelaDePrecos.Preco(new BigDecimal("10"), new BigDecimal("40")))));
            runnerGasta(new McpAgentRunner.Result(
                    "{\"parametro\":{},\"resposta\":\"\"}", List.of()));

            dispatcher.runAndReport(consulta("faz 8?"));

            VendaxResult.Uso uso = unico().uso();
            assertThat(uso.model()).isEqualTo("openrouter/google/gemini-2.5-flash-lite");
            assertThat(uso.tokens()).isEqualTo(812);
            assertThat(uso.costCents()).isEqualTo(1);
        }

        @Test
        @DisplayName("sem preço declarado: tokens seguem, custo nulo")
        void semPreco() {
            runnerGasta(new McpAgentRunner.Result(
                    "{\"parametro\":{},\"resposta\":\"\"}", List.of()));

            dispatcher.runAndReport(consulta("faz 8?"));

            VendaxResult.Uso uso = unico().uso();
            assertThat(uso.tokens()).isEqualTo(812);
            assertThat(uso.costCents()).isNull();
        }

        /** Falhou, mas gastou — e é essa a execução que um teto mais precisa ver. */
        @Test
        @DisplayName("ERROR também leva o que foi gasto")
        void erroLevaUso() {
            McpAgentRunner.ToolCall recusada = new McpAgentRunner.ToolCall("plano_negociacao",
                    Map.of(), "ERRO", true, ToolTrust.TRUSTED, -32001);
            runnerGasta(new McpAgentRunner.Result("", List.of(recusada), null, recusada));

            dispatcher.runAndReport(consulta("faz 8?"));

            VendaxResult r = unico();
            assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(r.uso().tokens()).isEqualTo(812);
        }

        @Test
        @DisplayName("nenhum modelo chamado: o campo é omitido")
        void semConsumo() throws Exception {
            dispatcher.runAndReport(consulta("faz 8?", "ns:negociacao"));

            VendaxResult r = unico();
            assertThat(r.uso()).isNull();
            assertThat(MAPPER.readTree(MAPPER.writeValueAsString(r)).has("uso"))
                    .as("ausente é 'nada a relatar', não 'custo zero'")
                    .isFalse();
        }

        @Test
        @DisplayName("no JSON, o uso tem a forma do contrato")
        void formaDoJson() throws Exception {
            VendaxResult r = VendaxResult.ok(consulta("x"), "ns_consulta", "{}")
                    .comUso(new VendaxResult.Uso("openrouter/m", 812L, null));

            JsonNode uso = MAPPER.readTree(MAPPER.writeValueAsString(r)).path("uso");
            assertThat(uso.path("model").asText()).isEqualTo("openrouter/m");
            assertThat(uso.path("tokens").asLong()).isEqualTo(812);
            assertThat(uso.has("costCents")).isTrue();
            assertThat(uso.path("costCents").isNull())
                    .as("nulo explícito: ninguém declarou o preço")
                    .isTrue();
        }
    }
}
