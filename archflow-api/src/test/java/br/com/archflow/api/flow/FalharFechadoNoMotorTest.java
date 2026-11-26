package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-123 e RCHFLW-135 no motor de verdade (deserializer, validador, executor e os nós reais).
 *
 * <p>No QA o "Gerar com IA" montou Entrada → Condição {@code ${entrada.valor} > 500} → true: aprovação / false:
 * LLM. Com o texto real de um cliente ("quero reembolso de 900 reais…") a condição deu {@code false} sem aviso, e o
 * LLM confirmou o reembolso de R$ 900 sem ninguém aprovar. Agora a referência sem valor falha o passo e nenhum ramo
 * roda; {@code archflow.flow.lenient-conditions=true} devolve o comportamento antigo. O switch sem aresta para o ramo
 * também falha, e o nó solto é recusado antes do primeiro passo.</p>
 */
@DisplayName("falhar fechado: referência sem valor, switch sem saída e nó solto (RCHFLW-123/135)")
class FalharFechadoNoMotorTest {

    private static final String TEXTO_DO_CLIENTE =
            "Olá, sou a Carla e quero reembolso de 900 reais do pedido 555, veio quebrado.";

    private static DefaultFlowStepFactory fabrica(boolean leniente) {
        return fabrica(leniente, false);
    }

    /** @param estrito o {@code archflow.flow.strict-conditions} — o mesmo valor vai ao motor, em {@link #executar}. */
    private static DefaultFlowStepFactory fabrica(boolean leniente, boolean estrito) {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        DefaultFlowStepFactory fabrica = new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
        fabrica.setLenientConditionsProperty(leniente);
        fabrica.setStrictConditionsProperty(estrito);
        return fabrica;
    }

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", label);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    private static Flow fluxo(boolean leniente, List<Map<String, Object>> passos) {
        return new DefaultWorkflowDeserializer(fabrica(leniente)).toFlow(Map.of("id", "reembolso",
                "metadata", Map.of("name", "reembolso"), "steps", passos));
    }

    /** Receber pedido → Acima de 500? → true: Aprovação do gerente / false: Resposta automática. */
    private static Flow portaoDeValor(boolean leniente, String expressao) {
        return fluxo(leniente, List.of(
                no("receber", "input", "Receber pedido", Map.of("ioFields", List.of("valor", "cliente")),
                        List.of(Map.of("targetId", "acima"))),
                no("acima", "condition", "Acima de 500?", Map.of("conditionExpression", expressao),
                        List.of(Map.of("targetId", "gerente", "branch", "true"),
                                Map.of("targetId", "automatico", "branch", "false"))),
                no("gerente", "output", "Aprovação do gerente", Map.of(), List.of()),
                no("automatico", "output", "Resposta automática", Map.of(), List.of())));
    }

    /** O contexto como o servidor o semeia: cada chave do JSON vira variável, texto vira o input. */
    private static DefaultExecutionContext contexto(String flowId, Object entrada) {
        DefaultExecutionContext ctx = new DefaultExecutionContext("t1", "u1", flowId,
                MessageWindowChatMemory.builder().maxMessages(5).build());
        if (entrada instanceof Map<?, ?> mapa) {
            @SuppressWarnings("unchecked")
            Map<String, Object> variaveis = (Map<String, Object>) mapa;
            RunInputSeeding.seedVariables(ctx, variaveis);
        } else {
            RunInputSeeding.seedVariables(ctx, entrada == null ? null : Map.of("input", entrada));
        }
        return ctx;
    }

    private static FlowResult executar(Flow flow, DefaultExecutionContext ctx) throws Exception {
        FlowEngine motor = FlowEngineFactory.create(new InMemoryFlowRepository(), null, null,
                new InMemoryStateManager());
        return motor.execute(flow, ctx).get(15, TimeUnit.SECONDS);
    }

    /** O motor com {@code archflow.flow.strict-conditions=true}, como o {@code flowEngine} do servidor o monta. */
    private static FlowResult executarEstrito(Flow flow, DefaultExecutionContext ctx) throws Exception {
        FlowEngine motor = FlowEngineFactory.create(new InMemoryFlowRepository(), null, null,
                new InMemoryStateManager(), null, null, 16, 3_600_000L, 8, true);
        return motor.execute(flow, ctx).get(15, TimeUnit.SECONDS);
    }

    private static String erros(FlowResult r) {
        return String.join(" | ", r.getErrors().stream().map(ExecutionError::message).toList());
    }

    @Nested
    @DisplayName("condition (RCHFLW-123)")
    class Condicao {

        @Test
        @DisplayName("texto livre num portão por ${entrada.valor}: o passo falha nomeando nó, campo e referência")
        void textoLivreFalhaOPortao() throws Exception {
            Flow flow = portaoDeValor(false, "${receber.valor} > 500");
            DefaultExecutionContext ctx = contexto(flow.getId(), TEXTO_DO_CLIENTE);

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(erros(r))
                    .contains("condition \"Acima de 500?\" (acima), campo conditionExpression")
                    .contains("a referência ${receber.valor} não tem valor nesta execução")
                    .contains("falha em vez de escolher um ramo");
            // Nenhum ramo rodou: nem a resposta automática (o defeito), nem a aprovação.
            assertThat(ctx.get("automatico")).isEmpty();
            assertThat(ctx.get("gerente")).isEmpty();
        }

        @Test
        @DisplayName("com o JSON que traz o campo, o portão escolhe pelo valor, como antes")
        void jsonComOCampoEscolheORamo() throws Exception {
            Flow flow = portaoDeValor(false, "${valor} > 500");

            DefaultExecutionContext alto = contexto(flow.getId(), Map.of("valor", 900, "cliente", "Carla"));
            FlowResult r1 = executar(flow, alto);
            DefaultExecutionContext baixo = contexto(flow.getId(), Map.of("valor", 120, "cliente", "Rui"));
            FlowResult r2 = executar(flow, baixo);

            assertThat(r1.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(alto.get("gerente")).isPresent();
            assertThat(alto.get("automatico")).isEmpty();
            assertThat(r2.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(baixo.get("automatico")).isPresent();
            assertThat(baixo.get("gerente")).isEmpty();
        }

        @Test
        @DisplayName("o número que um LLM em pt-BR devolve («1.200,00», «R$ 120») não decide o portão: falha")
        void numeroEmTextoNaoDecide() throws Exception {
            // A ordem do texto mandava R$ 1.200 ao automático («1» antes de «5») e R$ 120 ao gerente.
            Flow flow = portaoDeValor(false, "${valor_llm} > 500");
            DefaultExecutionContext milhar = contexto(flow.getId(), Map.of("input", "oi", "valor_llm", "1.200,00"));

            FlowResult r = executar(flow, milhar);

            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(erros(r)).contains("condition \"Acima de 500?\" (acima), campo conditionExpression")
                    .contains("põe um número contra ${valor_llm} = «1.200,00», que não é número");
            assertThat(milhar.get("automatico")).isEmpty();
            assertThat(milhar.get("gerente")).isEmpty();

            DefaultExecutionContext digitos = contexto(flow.getId(), Map.of("input", "oi", "valor_llm", "1200"));
            assertThat(executar(flow, digitos).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(digitos.get("gerente")).isPresent();

            // Os formatos da revisão: nenhum escolhe um ramo — o passo falha nomeando o valor.
            for (String ptBr : List.of("1.500,00", "R$ 900", "900 reais")) {
                DefaultExecutionContext c = contexto(flow.getId(), Map.of("input", "oi", "valor_llm", ptBr));
                FlowResult falhou = executar(flow, c);
                assertThat(falhou.getStatus()).as(ptBr).isEqualTo(ExecutionStatus.FAILED);
                assertThat(erros(falhou)).as(ptBr).contains("${valor_llm} = «" + ptBr + "», que não é número")
                        .contains("Extraia o valor com um nó decision");
                assertThat(c.get("automatico")).as(ptBr).isEmpty();
                assertThat(c.get("gerente")).as(ptBr).isEmpty();
            }
            // O limite do avaliador, e o motivo de os assistentes não pedirem o número a um LLM: «1.500» é um
            // número válido (1,5) e decide — o ramo errado para o «mil e quinhentos» de um texto em pt-BR.
            DefaultExecutionContext milEQuinhentos = contexto(flow.getId(),
                    Map.of("input", "oi", "valor_llm", "1.500"));
            assertThat(executar(flow, milEQuinhentos).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(milEQuinhentos.get("automatico")).isPresent();
        }

        @Test
        @DisplayName("archflow.flow.lenient-conditions=true: o comportamento antigo — ramo false, com o aviso")
        void lenienteVoltaAoRamoFalso() throws Exception {
            Flow flow = portaoDeValor(true, "${receber.valor} > 500");
            DefaultExecutionContext ctx = contexto(flow.getId(), TEXTO_DO_CLIENTE);

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(ctx.get("automatico")).isPresent();
            assertThat(ctx.get("gerente")).isEmpty();
            assertThat(String.valueOf(ctx.get("acima").orElseThrow())).contains("branch=false");
        }

        @Test
        @DisplayName("entrada vazia: ${input} vale \"\", e ${input} != '' segue o false (RCHFLW-101)")
        void entradaVaziaContinuaUmValor() throws Exception {
            Flow flow = portaoDeValor(false, "${input} != ''");
            DefaultExecutionContext vazio = contexto(flow.getId(), null);
            FlowResult r = executar(flow, vazio);

            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(vazio.get("automatico")).isPresent();
            assertThat(vazio.get("gerente")).isEmpty();
        }

        @Test
        @DisplayName("a referência que falta não derruba o passo quando o resultado não depende dela (|| e &&)")
        void decisaoQueFalhouAindaVaiAoHumano() throws Exception {
            // O exemplo que o assistente ensina: a decisão que falhou não tem respostas, mas tem route ESCALATE.
            Flow flow = portaoDeValor(false,
                    "${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'");
            Map<String, Object> falhou = new LinkedHashMap<>();
            falhou.put("route", "ESCALATE");
            falhou.put("decision", null);
            falhou.put("answers", Map.of());
            DefaultExecutionContext ctx = contexto(flow.getId(), Map.of("input", "oi", "triagem", falhou));

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).as(erros(r)).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(ctx.get("gerente")).isPresent();

            // Com route AUTO e sem a resposta, o resultado depende do que falta: falha.
            Map<String, Object> semResposta = new LinkedHashMap<>(falhou);
            semResposta.put("route", "AUTO");
            DefaultExecutionContext ctx2 = contexto(flow.getId(), Map.of("input", "oi", "triagem", semResposta));
            FlowResult r2 = executar(flow, ctx2);
            assertThat(r2.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(erros(r2)).contains("${triagem.answers.urgente.result}");
            assertThat(ctx2.get("automatico")).isEmpty();
        }
    }

    @Nested
    @DisplayName("switch (RCHFLW-123 e RCHFLW-135)")
    class Switch {

        private Flow porCategoria(boolean leniente, boolean comDefault) {
            List<Map<String, Object>> arestas = new ArrayList<>(List.of(
                    Map.of("targetId", "financeiro", "branch", "financeiro"),
                    Map.of("targetId", "suporte", "branch", "suporte")));
            List<Map<String, Object>> passos = new ArrayList<>(List.of(
                    no("receber", "input", "Receber pedido", Map.of(), List.of(Map.of("targetId", "rota"))),
                    no("rota", "switch", "Por categoria", Map.of("switchExpression", "${categoria}",
                            "switchCases", "financeiro\nsuporte"), arestas),
                    no("financeiro", "output", "Financeiro", Map.of(), List.of()),
                    no("suporte", "output", "Suporte", Map.of(), List.of())));
            if (comDefault) {
                arestas.add(Map.of("targetId", "humano", "branch", "default"));
                passos.add(no("humano", "output", "Humano", Map.of(), List.of()));
            }
            return fluxo(leniente, passos);
        }

        @Test
        @DisplayName("sem caso para o valor e sem aresta default: falha dizendo o valor e os casos")
        void semCasoNemDefaultFalha() throws Exception {
            Flow flow = porCategoria(false, false);
            DefaultExecutionContext ctx = contexto(flow.getId(), Map.of("categoria", "vendas"));

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(erros(r))
                    .contains("switch \"Por categoria\" (rota)")
                    .contains("o valor «vendas» não casa com nenhum caso (financeiro, suporte) e nenhuma aresta "
                            + "de saída seria seguida pelo ramo default");
        }

        @Test
        @DisplayName("com aresta default, o valor sem caso vai a ela; o caso que casa segue o seu ramo")
        void comDefaultSegue() throws Exception {
            Flow flow = porCategoria(false, true);
            DefaultExecutionContext outro = contexto(flow.getId(), Map.of("categoria", "vendas"));
            DefaultExecutionContext suporte = contexto(flow.getId(), Map.of("categoria", "Suporte"));

            assertThat(executar(flow, outro).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(outro.get("humano")).isPresent();
            assertThat(executar(flow, suporte).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(suporte.get("suporte")).isPresent();
            assertThat(suporte.get("humano")).isEmpty();
        }

        @Test
        @DisplayName("referência sem valor no switch falha, mesmo com default; o nulo gravado vai ao default")
        void referenciaSemValorFalha() throws Exception {
            Flow flow = porCategoria(false, true);
            DefaultExecutionContext texto = contexto(flow.getId(), TEXTO_DO_CLIENTE);
            FlowResult r = executar(flow, texto);
            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(erros(r)).contains("campo switchExpression: a referência ${categoria} não tem valor");
            assertThat(texto.get("humano")).isEmpty();

            // O decision que falhou grava decision: null — é valor ("não sei"), e o default leva ao humano.
            Flow porDecisao = fluxo(false, List.of(
                    no("receber", "input", "Receber", Map.of(), List.of(Map.of("targetId", "rota"))),
                    no("rota", "switch", "Por categoria", Map.of("switchExpression", "${triagem.decision}",
                            "switchCases", "financeiro"), List.of(
                                    Map.of("targetId", "financeiro", "branch", "financeiro"),
                                    Map.of("targetId", "humano", "branch", "default"))),
                    no("financeiro", "output", "Financeiro", Map.of(), List.of()),
                    no("humano", "output", "Humano", Map.of(), List.of())));
            Map<String, Object> falhou = new LinkedHashMap<>();
            falhou.put("route", "ESCALATE");
            falhou.put("decision", null);
            DefaultExecutionContext ctx = contexto(porDecisao.getId(), Map.of("input", "oi", "triagem", falhou));
            FlowResult r2 = executar(porDecisao, ctx);
            assertThat(r2.getStatus()).as(erros(r2)).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(ctx.get("humano")).isPresent();
        }

        @Test
        @DisplayName("caso declarado que casa e não tem aresta conclui: é o fim deliberado, como antes")
        void casoSemArestaConclui() throws Exception {
            // 'spam' é caso declarado e sem aresta de propósito: termina ali, como a condition só com o true.
            Flow flow = fluxo(false, List.of(
                    no("receber", "input", "Receber", Map.of(), List.of(Map.of("targetId", "rota"))),
                    no("rota", "switch", "Por categoria", Map.of("switchExpression", "${categoria}",
                            "switchCases", "vendas\nspam"), List.of(
                                    Map.of("targetId", "vendas", "branch", "vendas"),
                                    Map.of("targetId", "humano", "branch", "default"))),
                    no("vendas", "output", "Vendas", Map.of(), List.of()),
                    no("humano", "output", "Humano", Map.of(), List.of())));
            DefaultExecutionContext ctx = contexto(flow.getId(), Map.of("categoria", "spam"));

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).as(erros(r)).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(ctx.get("vendas")).isEmpty();
            assertThat(ctx.get("humano")).isEmpty();
        }

        @Test
        @DisplayName("aresta escrita à mão que não se avalia: o motor a segue, e o switch não falha por ela")
        void arestaNaoAvaliavelNaoContaComoSemSaida() throws Exception {
            // ${rota.value} > 5 com o valor nulo (o decision que falhou) lança; o motor padrão segue a aresta.
            Flow flow = fluxo(false, List.of(
                    no("receber", "input", "Receber", Map.of(), List.of(Map.of("targetId", "rota"))),
                    no("rota", "switch", "Por categoria", Map.of("switchExpression", "${triagem.decision}",
                            "switchCases", "financeiro"), List.of(
                                    Map.of("targetId", "humano", "condition", "${rota.value} > 5"))),
                    no("humano", "output", "Humano", Map.of(), List.of())));
            Map<String, Object> falhou = new LinkedHashMap<>();
            falhou.put("route", "ESCALATE");
            falhou.put("decision", null);
            DefaultExecutionContext ctx = contexto(flow.getId(), Map.of("input", "oi", "triagem", falhou));

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).as(erros(r)).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(ctx.get("humano")).isPresent();
        }

        @Test
        @DisplayName("archflow.flow.strict-conditions=true: a aresta que não se avalia não é seguida, e o switch falha")
        void arestaNaoAvaliavelNoMotorEstritoFalha() throws Exception {
            // O motor estrito bloqueia ${rota.value} > 5 com o valor nulo. Conferido no modo permissivo, o switch
            // contava a aresta como seguida, concluía — e o fluxo terminava COMPLETED sem chegar ao humano.
            List<Map<String, Object>> passos = List.of(
                    no("receber", "input", "Receber", Map.of(), List.of(Map.of("targetId", "rota"))),
                    no("rota", "switch", "Por categoria", Map.of("switchExpression", "${triagem.decision}",
                            "switchCases", "financeiro"), List.of(
                                    Map.of("targetId", "humano", "condition", "${rota.value} > 5"))),
                    no("humano", "output", "Humano", Map.of(), List.of()));
            Flow flow = new DefaultWorkflowDeserializer(fabrica(false, true)).toFlow(Map.of("id", "estrito",
                    "metadata", Map.of("name", "estrito"), "steps", passos));
            Map<String, Object> falhou = new LinkedHashMap<>();
            falhou.put("route", "ESCALATE");
            falhou.put("decision", null);
            DefaultExecutionContext ctx = contexto(flow.getId(), Map.of("input", "oi", "triagem", falhou));

            FlowResult r = executarEstrito(flow, ctx);

            assertThat(r.getStatus()).as(erros(r)).isEqualTo(ExecutionStatus.FAILED);
            assertThat(erros(r)).contains("switch \"Por categoria\" (rota)")
                    .contains("nenhuma aresta de saída seria seguida pelo ramo default");
            assertThat(ctx.get("humano")).isEmpty();
        }

        @Test
        @DisplayName("chave com ponto («step.n1.output»): o switch lê o valor pelo mesmo caminho que confere")
        void chaveComPontoLidaComoNaConferencia() {
            // O StateTemplate só descia do 1º segmento ('step', ausente) e lia null; o hasPath achava o prefixo.
            ExecutionContext ctx = contexto("f", Map.of("input", "oi"));
            ctx.set("step.n1.output", Map.of("field", "x"));
            var passo = fabrica(false).create(no("rota", "switch", "Por campo", Map.of(
                    "switchExpression", "${step.n1.output.field}", "switchCases", "x"), List.of()));

            var r = passo.execute(ctx).join();

            assertThat(r.getStatus()).isEqualTo(br.com.archflow.model.enums.StepStatus.COMPLETED);
            assertThat(String.valueOf(ctx.get("rota").orElseThrow())).contains("branch=x");
        }

        @Test
        @DisplayName("archflow.flow.lenient-conditions=true: sem caso nem default conclui, como antes")
        void lenienteConclui() throws Exception {
            Flow flow = porCategoria(true, false);
            DefaultExecutionContext ctx = contexto(flow.getId(), Map.of("categoria", "vendas"));

            FlowResult r = executar(flow, ctx);

            assertThat(r.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(ctx.get("financeiro")).isEmpty();
            assertThat(ctx.get("suporte")).isEmpty();
        }
    }

    @Nested
    @DisplayName("nó solto (RCHFLW-135)")
    class NoSolto {

        @Test
        @DisplayName("com arestas, um nó sem aresta é recusado antes do primeiro passo, nomeado")
        void noSoltoRecusadoNoInicio() {
            // O QA religou a aprovação para outra Saída; a antiga ficou solta e rodou antes do portão.
            Flow flow = fluxo(false, List.of(
                    no("receber", "input", "Receber pedido", Map.of(), List.of(Map.of("targetId", "nova"))),
                    no("nova", "output", "Saída nova", Map.of(), List.of()),
                    no("antiga", "output", "Saída antiga", Map.of(), List.of())));
            DefaultExecutionContext ctx = contexto(flow.getId(), "oi");

            assertThatThrownBy(() -> executar(flow, ctx))
                    .isInstanceOf(ExecutionException.class)
                    .rootCause()
                    .isInstanceOf(FlowValidationException.class)
                    .hasMessageContaining("nó(s) sem nenhuma aresta: \"Saída antiga\" (antiga)")
                    .hasMessageContaining("Ligue-o(s) ao fluxo ou remova-o(s)");
            assertThat(ctx.get("receber")).isEmpty();
            assertThat(ctx.get("antiga")).isEmpty();
        }

        @Test
        @DisplayName("fluxo de um nó só e fluxo sem aresta nenhuma (linear) continuam rodando")
        void umNoELinearRodam() throws Exception {
            Flow umNo = fluxo(false, List.of(no("so", "output", "Só", Map.of(), List.of())));
            DefaultExecutionContext c1 = contexto(umNo.getId(), "oi");
            assertThat(executar(umNo, c1).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            Flow linear = fluxo(false, List.of(
                    no("a", "input", "A", Map.of(), List.of()),
                    no("b", "output", "B", Map.of(), List.of())));
            DefaultExecutionContext c2 = contexto(linear.getId(), "oi");
            assertThat(executar(linear, c2).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(c2.get("b")).isPresent();
        }
    }
}
