package br.com.archflow.api.agent.vendax;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.decision.DecisionKeys;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nós {@code condition}/{@code switch} num fluxo que chega pelo invoke do VendaX, depois da RCHFLW-123/135 — motor,
 * fábrica, deserializer, runner e dispatcher reais; os nós finais são componentes determinísticos.
 *
 * <p>O {@link AgentFlowRunner} põe no contexto só o que veio ({@code definirSeHouver}): {@code texto} sem texto,
 * {@code payload} sem payload-objeto e as chaves {@code vendax.*} (task, cliente) ficam <b>ausentes</b> — "o caso
 * comum". Uma {@code condition}/{@code switch} que lê um desses campos passou a falhar o fluxo (o Core recebe
 * {@code ERROR}) onde antes seguia {@code false}/{@code default}. O mesmo vale para a decisão que falhou lida por
 * {@code answers.<id>.*}: sem respostas, o switch não vai mais ao {@code default} (o humano). Os desenhos que a
 * documentação ensina ({@code ${D.decision}} no switch e {@code answers... || route == 'ESCALATE'}) seguem
 * chegando ao humano.</p>
 */
@DisplayName("VendaX: condition/switch sobre campo opcional do invoke e sobre a decisão que falhou (RCHFLW-123)")
class NosDeControleNoInvokeTest {

    /** Um nó final que devolve {@code {"ramo": "<id>"}} — a saída diz qual ramo o fluxo seguiu. */
    private static AIComponent ramo(String id) {
        return new AIComponent() {
            public void initialize(Map<String, Object> config) { }
            public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, id, "fixture", ComponentType.TOOL, "1.0.0", Set.of(), List.of(),
                        Map.of(), Set.of());
            }
            public Object execute(String op, Object in, ExecutionContext ctx) {
                return Map.of("text", "{\"ramo\":\"" + id + "\"}");
            }
            public void shutdown() { }
        };
    }

    private static Map<String, Object> no(String id, String type, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    private static Map<String, Object> aresta(String alvo, String ramo) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("targetId", alvo);
        if (ramo != null) {
            a.put("branch", ramo);
        }
        return a;
    }

    private static VendaxResult executar(boolean leniente, List<Map<String, Object>> passos, String texto,
                                         String payload, String taskId) {
        var catalogo = new DefaultComponentCatalog();
        catalogo.register(ramo("ramo-a"));
        catalogo.register(ramo("ramo-b"));
        var repo = new InMemoryFlowRepository();
        var engine = FlowEngineFactory.create(repo);
        var beans = new StaticListableBeanFactory();
        beans.addBean("engine", engine);
        var fabrica = new DefaultFlowStepFactory(catalogo, null, null, null, null);
        fabrica.setLenientConditionsProperty(leniente);
        // Sem chave nenhuma: o http-decisions não chama, e o nó escala com a saída degradada (route ESCALATE,
        // decision null, answers vazio) — a decisão que falhou.
        fabrica.setDecisionSupport(DecisionProviders.withDefaults(), DecisionKeys.NONE);
        var runner = new AgentFlowRunner(new DefaultWorkflowDeserializer(fabrica),
                beans.getBeanProvider(br.com.archflow.engine.api.FlowEngine.class), repo, null);
        var resultados = new ArrayList<VendaxResult>();
        var sender = new VendaxResultSender("http://nao-chamar.invalid", "segredo-sintetico") {
            @Override public void send(VendaxResult r, String trace) { resultados.add(r); }
        };
        Map<String, Object> documento = Map.of("id", "controle", "metadata", Map.of("name", "Controle"),
                "steps", passos);
        var invoke = new VendaxInvoke("1.0", "tenant-teste", "conversa-teste", "CS", "m1", texto, "LIGHT",
                "teste", "trace-teste", payload, null, null, "controle:1", taskId,
                new DefinicaoDeAgente("FLUXO", documento, null, "ramo@1", List.of(), Map.of(), Map.of(), "c@1"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new VendaxAgentDispatcher(null, null, null, sender, executor, null, runner).runAndReport(invoke);
        }
        assertThat(resultados).hasSize(1);
        return resultados.get(0);
    }

    /** Condição sobre um campo do invoke → true: ramo-a / false: ramo-b. */
    private static List<Map<String, Object>> condicao(String expressao) {
        return List.of(
                no("filtro", "condition", Map.of("conditionExpression", expressao),
                        List.of(aresta("a", "true"), aresta("b", "false"))),
                no("a", "ramo-a", Map.of(), List.of()),
                no("b", "ramo-b", Map.of(), List.of()));
    }

    /** Decisão (que vai falhar por falta de chave) → switch sobre {@code expressao}; default vai ao ramo-b. */
    private static List<Map<String, Object>> decisaoESwitch(String expressao) {
        Map<String, Object> perguntas = Map.of("intencao", Map.of("type", "choice",
                "instructions", "O cliente está fechando um pedido?",
                "criteria", Map.of("PEDIDO", "confirma itens", "DUVIDA", "pergunta")));
        return List.of(
                no("triagem", "decision", Map.of("provider", "http-decisions", "model", "typesafe/jev-1.14",
                        "questions", perguntas, "primary", "intencao"), List.of(aresta("rota", null))),
                no("rota", "switch", Map.of("switchExpression", expressao, "switchCases", "PEDIDO\nDUVIDA"),
                        List.of(aresta("a", "PEDIDO"), aresta("b", "default"))),
                no("a", "ramo-a", Map.of(), List.of()),
                no("b", "ramo-b", Map.of(), List.of()));
    }

    @Nested
    @DisplayName("campo opcional do invoke ausente")
    class CampoOpcional {

        @Test
        @DisplayName("${payload.canal} sem payload: antes ramo false (OK); agora o Core recebe ERROR")
        void payloadAusente() {
            var antes = executar(true, condicao("${payload.canal} == 'whatsapp'"), "oi", null, null);
            var agora = executar(false, condicao("${payload.canal} == 'whatsapp'"), "oi", null, null);

            assertThat(antes.status()).isEqualTo(VendaxResult.OK);
            assertThat(antes.richObject()).contains("ramo-b");
            assertThat(agora.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(agora.error()).contains("${payload.canal}").contains("não tem valor");
        }

        @Test
        @DisplayName("${payload.canal} com payload sem o campo: o mesmo ERROR")
        void campoAusenteNoPayload() {
            var agora = executar(false, condicao("${payload.canal} == 'whatsapp'"), "oi", "{\"sku\":\"1\"}", null);

            assertThat(agora.status()).isEqualTo(VendaxResult.ERROR);
        }

        @Test
        @DisplayName("${vendax.origem.task} na conversa sem task (o caso comum): antes seguia; agora ERROR")
        void taskAusente() {
            var antes = executar(true, condicao("${vendax.origem.task} != ''"), "oi", null, null);
            var agora = executar(false, condicao("${vendax.origem.task} != ''"), "oi", null, null);
            var comTask = executar(false, condicao("${vendax.origem.task} != ''"), "oi", null, "task-9");

            // Antes: null != '' é verdadeiro — a conversa SEM task seguia o ramo do "tem task".
            assertThat(antes.status()).isEqualTo(VendaxResult.OK);
            assertThat(antes.richObject()).contains("ramo-a");
            assertThat(agora.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(comTask.status()).isEqualTo(VendaxResult.OK);
            assertThat(comTask.richObject()).contains("ramo-a");
        }

        @Test
        @DisplayName("${texto} sem texto (invoke só com payload/imagem): antes ramo false; agora ERROR")
        void textoAusente() {
            var antes = executar(true, condicao("${texto} contains 'pedido'"), null, "{\"x\":1}", null);
            var agora = executar(false, condicao("${texto} contains 'pedido'"), null, "{\"x\":1}", null);

            assertThat(antes.status()).isEqualTo(VendaxResult.OK);
            assertThat(antes.richObject()).contains("ramo-b");
            assertThat(agora.status()).isEqualTo(VendaxResult.ERROR);
        }

        @Test
        @DisplayName("${input} nunca falta no invoke: o runner sempre monta a entrada do agente")
        void inputSempreExiste() {
            var agora = executar(false, condicao("${input} contains 'modoEntrada'"), null, null, null);

            assertThat(agora.status()).isEqualTo(VendaxResult.OK);
            assertThat(agora.richObject()).contains("ramo-a");
        }
    }

    @Nested
    @DisplayName("decisão que falhou (sem chave: route ESCALATE, decision null, answers vazio)")
    class DecisaoQueFalhou {

        @Test
        @DisplayName("switch em ${triagem.decision}: vai ao default (humano) antes e agora")
        void switchPelaDecisao() {
            var antes = executar(true, decisaoESwitch("${triagem.decision}"), "quero 3 caixas", null, null);
            var agora = executar(false, decisaoESwitch("${triagem.decision}"), "quero 3 caixas", null, null);

            assertThat(antes.richObject()).contains("ramo-b");
            assertThat(agora.status()).isEqualTo(VendaxResult.OK);
            assertThat(agora.richObject()).contains("ramo-b");
        }

        @Test
        @DisplayName("switch em ${triagem.answers.intencao.choice}: antes default (humano); agora ERROR")
        void switchPelaResposta() {
            var antes = executar(true, decisaoESwitch("${triagem.answers.intencao.choice}"), "quero 3 caixas",
                    null, null);
            var agora = executar(false, decisaoESwitch("${triagem.answers.intencao.choice}"), "quero 3 caixas",
                    null, null);

            assertThat(antes.status()).isEqualTo(VendaxResult.OK);
            assertThat(antes.richObject()).contains("ramo-b");
            assertThat(agora.status()).isEqualTo(VendaxResult.ERROR);
            assertThat(agora.error()).contains("${triagem.answers.intencao.choice}");
        }

        @Test
        @DisplayName("condition answers... || route == 'ESCALATE' (o desenho documentado): humano antes e agora")
        void condicaoComPortaoDeRota() {
            List<Map<String, Object>> passos = new ArrayList<>(decisaoESwitch("${triagem.decision}"));
            passos.set(1, no("rota", "condition", Map.of("conditionExpression",
                            "${triagem.answers.intencao.choice} == 'PEDIDO' || ${triagem.route} == 'ESCALATE'"),
                    List.of(aresta("b", "true"), aresta("a", "false"))));

            var agora = executar(false, passos, "quero 3 caixas", null, null);

            assertThat(agora.status()).isEqualTo(VendaxResult.OK);
            assertThat(agora.richObject()).contains("ramo-b");
        }
    }

    @Test
    @DisplayName("nó solto num fluxo com arestas: ERROR mesmo com lenient-conditions=true (sem escape)")
    void noSoltoNaoTemEscape() {
        List<Map<String, Object>> passos = List.of(
                no("a", "ramo-a", Map.of(), List.of(aresta("b", null))),
                no("b", "ramo-b", Map.of(), List.of()),
                no("sobra", "ramo-a", Map.of(), List.of()));

        var leniente = executar(true, passos, "oi", null, null);

        assertThat(leniente.status()).isEqualTo(VendaxResult.ERROR);
        assertThat(leniente.error()).contains("sem nenhuma aresta").contains("sobra");
    }

    @Test
    @DisplayName("fluxo recusado na validação: o Core recebe o motivo, não o invólucro do motor")
    void recusaNaValidacaoChegaAoCoreComOMotivo() {
        List<Map<String, Object>> passos = List.of(
                no("a", "ramo-a", Map.of(), List.of(aresta("b", null))),
                no("b", "ramo-b", Map.of(), List.of()),
                no("sobra", "ramo-a", Map.of(), List.of()));

        var resultado = executar(false, passos, "oi", null, null);

        // A FlowValidationException fica dois níveis abaixo (ExecutionException → FlowEngineException "Error
        // executing flow: <uuid>"). Lendo um nível só, o Core recebia o uuid da execução no lugar do motivo — que a
        // tela de execuções já mostrava, pelo SpringWorkflowCrudController.errorOf.
        assertThat(resultado.status()).isEqualTo(VendaxResult.ERROR);
        assertThat(resultado.error())
                .startsWith("Falha ao executar o fluxo: ")
                .contains("nó(s) sem nenhuma aresta: ")
                .contains("sobra")
                .doesNotContain("Error executing flow")
                .doesNotContain("\tat ");
    }
}
