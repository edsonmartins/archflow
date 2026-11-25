package br.com.archflow.api.flow;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.validation.DefaultFlowValidator;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fábrica comum sem o servidor (RCHFLW-37): os nós que só o servidor constrói viram recusa antes do primeiro
 * passo, e com o servidor ({@link ServerNodes}) a construção é dele — na mesma posição da ordem de sempre.
 */
@DisplayName("PortableFlowStepFactory")
class PortableFlowStepFactoryTest {

    private static Map<String, Object> no(String id, String type, String componentId, Map<String, Object> config) {
        Map<String, Object> node = new java.util.HashMap<>();
        node.put("id", id);
        node.put("type", type);
        if (componentId != null) {
            node.put("componentId", componentId);
        }
        node.put("configuration", config);
        return node;
    }

    private static final List<Map<String, Object>> NOS_DO_SERVIDOR = List.of(
            no("orq", "ORCHESTRATE", null, Map.of("goal", "x")),
            no("aprova", "APPROVAL", null, Map.of()),
            no("agente", "agent", "mcp-agent", Map.of("systemPrompt", "x")),
            no("busca", "vector-search", "vector-search", Map.of("knowledgeBaseId", "kb-1")));

    @Test
    @DisplayName("sem servidor, cada nó do servidor vira ServerOnlyNodeStep com o motivo certo")
    void semServidor() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);

        List<FlowStep> passos = NOS_DO_SERVIDOR.stream().map(factory::create).toList();

        assertThat(passos).allSatisfy(p -> assertThat(p).isInstanceOf(ServerOnlyNodeStep.class));
        assertThat(passos).extracting(p -> ((ServerOnlyNodeStep) p).capability()).containsExactly(
                ServerCapability.ORCHESTRATE, ServerCapability.APPROVAL, ServerCapability.MCP_AGENT,
                ServerCapability.KNOWLEDGE_BASE);
        // Executado por fora do motor, o passo falha com a mesma mensagem.
        var resultado = passos.get(1).execute(null).join();
        assertThat(resultado.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    @DisplayName("a validação recusa o fluxo antes do primeiro passo, um erro por nó, nomeando o nó")
    void validacaoRecusaAntes() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        Map<String, Object> entrada = new java.util.HashMap<>(no("entrada", "input", "input", Map.of()));
        entrada.put("connections", List.of(Map.of("targetId", "aprova")));
        Map<String, Object> aprova = new java.util.HashMap<>(no("aprova", "APPROVAL", null, Map.of()));
        aprova.put("label", "Revisão");
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(Map.of(
                "id", "f", "metadata", Map.of("name", "f"), "steps", List.of(entrada, aprova)));

        assertThatThrownBy(() -> new ExecutableNodesValidator(new DefaultFlowValidator()).validate(flow))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o nó \"Revisão\" (aprova) precisa do servidor archflow (aprovação humana")
                .satisfies(e -> assertThat(((FlowValidationException) e).getErrors())
                        .singleElement()
                        .satisfies(err -> assertThat(err.code()).isEqualTo(ExecutableNodesValidator.SERVER_CODE)));
    }

    @Test
    @DisplayName("com servidor, a construção é dele, com o nó já lido pela fábrica")
    void comServidor() {
        List<String> chamadas = new ArrayList<>();
        FlowStep marcador = new PassThroughStep("marcador", List.of());
        ServerNodes servidor = new ServerNodes() {
            @Override
            public FlowStep orchestrate(Request r) {
                chamadas.add("orchestrate:" + r.id());
                return marcador;
            }

            @Override
            public FlowStep approval(Request r) {
                chamadas.add("approval:" + r.id());
                return marcador;
            }

            @Override
            public FlowStep mcpAgent(Request r) {
                chamadas.add("mcpAgent:" + r.id() + ":" + r.config().get("systemPrompt") + ":" + r.operation());
                return marcador;
            }

            @Override
            public FlowStep knowledgeBase(Request r, boolean rag, AIComponent generator, String problem) {
                chamadas.add("knowledgeBase:" + r.id() + ":" + rag);
                return marcador;
            }
        };
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        factory.setServerNodes(servidor);

        NOS_DO_SERVIDOR.forEach(node -> assertThat(factory.create(node)).isSameAs(marcador));

        assertThat(chamadas).containsExactly("orchestrate:orq", "approval:aprova", "mcpAgent:agente:x:execute",
                "knowledgeBase:busca:false");
    }

    @Test
    @DisplayName("o resto não depende do servidor: controle, passagem e tipos sem execução")
    void naoDependeDoServidor() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);

        assertThat(factory.create(no("c", "condition", "condition", Map.of("conditionExpression", "true"))))
                .isInstanceOf(ControlStep.class);
        assertThat(factory.create(no("i", "input", "input", Map.of()))).isInstanceOf(PassThroughStep.class);
        assertThat(factory.create(no("t", "transform", "transform", Map.of()))).isInstanceOf(UnsupportedNodeStep.class);
        assertThat(factory.create(no("x", "x", "plugin-qualquer", Map.of()))).isInstanceOf(ComponentStep.class);
    }

    /** Um nó de decisão com uma pergunta sim/não — o mínimo que o componente aceita. */
    private static Map<String, Object> decisao(String id, Map<String, Object> extra) {
        Map<String, Object> config = new java.util.HashMap<>(Map.of("questions",
                Map.of("ok", Map.of("type", "noul", "instructions", "Está ok?"))));
        config.putAll(extra);
        return no(id, "decision", "decision", config);
    }

    @Test
    @DisplayName("sem servidor, decisão com provedor que só o servidor registra é recusada, dizendo qual falta")
    void decisaoComProvedorDoServidor() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);

        FlowStep llm = factory.create(decisao("triagem", Map.of("provider", "llm")));
        FlowStep noConsenso = factory.create(decisao("voto", Map.of("consensus",
                Map.of("models", List.of(Map.of("provider", "langchain4j-decisions", "model", "m"))))));
        FlowStep naReserva = factory.create(decisao("reserva", Map.of("provider", "rules",
                "fallbacks", List.of(Map.of("provider", "llm")))));

        assertThat(List.of(llm, noConsenso, naReserva)).allSatisfy(p -> assertThat(p)
                .isInstanceOfSatisfying(ServerOnlyNodeStep.class, s ->
                        assertThat(s.capability()).isEqualTo(ServerCapability.DECISION_PROVIDER)));
        assertThat(((ServerOnlyNodeStep) llm).message()).isEqualTo("o nó triagem precisa do servidor archflow "
                + "(decisão: o provedor escolhido é registrado só no servidor — provedor 'llm' não existe fora "
                + "do servidor (disponíveis aqui: http-decisions, rules))");
        assertThat(((ServerOnlyNodeStep) noConsenso).message()).contains("provedor 'langchain4j-decisions'");
    }

    @Test
    @DisplayName("sem servidor, decisão com os provedores embutidos é o passo de sempre")
    void decisaoComProvedorEmbutido() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);

        assertThat(factory.create(decisao("d", Map.of()))).isInstanceOf(ComponentStep.class);
        assertThat(factory.create(decisao("r", Map.of("provider", "rules")))).isInstanceOf(ComponentStep.class);
    }

    @Test
    @DisplayName("com servidor, decisão com provedor ausente continua um passo — a falha é da execução, "
            + "como antes")
    void decisaoNoServidorNaoMuda() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        factory.setServerNodes(new ServerNodesQueNaoConstroem());

        assertThat(factory.create(decisao("triagem", Map.of("provider", "llm")))).isInstanceOf(ComponentStep.class);
    }

    @Test
    @DisplayName("sem servidor, nó de dados sem adapter é recusado antes do primeiro passo, dizendo o backend")
    void noDeDadosSemAdapter() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);

        FlowStep busca = factory.create(no("busca", "vector-search", "vector-search",
                Map.of("vectorBackend", "pgvector")));
        FlowStep memoria = factory.create(no("memoria", "memory", "memory", Map.of()));

        assertThat(busca).isInstanceOfSatisfying(ServerOnlyNodeStep.class, s -> {
            assertThat(s.capability()).isEqualTo(ServerCapability.DATA_ADAPTER);
            assertThat(s.message()).startsWith("o nó busca precisa do servidor archflow (nó de dados")
                    .contains("o backend 'pgvector' não tem adapter de vectorstore fora do servidor")
                    .doesNotContain("neste servidor").doesNotContain("Base de conhecimento");
        });
        assertThat(memoria).isInstanceOfSatisfying(ServerOnlyNodeStep.class, s ->
                assertThat(s.message()).contains("o nó não escolheu o backend"));
    }

    @Test
    @DisplayName("nó de dados servido por um plugin com o tipo dele não é recusado; com servidor, nada muda")
    void noDeDadosComPluginOuServidor() {
        DefaultComponentCatalog catalogo = new DefaultComponentCatalog();
        catalogo.register(new AIComponent() {
            @Override
            public void initialize(Map<String, Object> config) {
            }

            @Override
            public br.com.archflow.model.ai.metadata.ComponentMetadata getMetadata() {
                return new br.com.archflow.model.ai.metadata.ComponentMetadata("vector-search", "Busca", "plugin",
                        br.com.archflow.model.ai.type.ComponentType.TOOL, "1.0.0", java.util.Set.of(), List.of(),
                        Map.of(), java.util.Set.of());
            }

            @Override
            public Object execute(String operation, Object input, br.com.archflow.model.engine.ExecutionContext c) {
                return input;
            }

            @Override
            public void shutdown() {
            }
        });
        PortableFlowStepFactory comPlugin = new PortableFlowStepFactory(catalogo, null, null);
        PortableFlowStepFactory noServidor = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        noServidor.setServerNodes(new ServerNodesQueNaoConstroem());
        Map<String, Object> busca = no("busca", "vector-search", "vector-search", Map.of("vectorBackend", "pgvector"));

        assertThat(comPlugin.create(busca)).isInstanceOf(ComponentStep.class);
        assertThat(noServidor.create(busca)).isInstanceOf(ComponentStep.class);
    }

    /** Um host de servidor que só existe para a fábrica saber que está no servidor. */
    private static final class ServerNodesQueNaoConstroem implements ServerNodes {
        @Override
        public FlowStep orchestrate(Request r) {
            throw new AssertionError("não usado");
        }

        @Override
        public FlowStep approval(Request r) {
            throw new AssertionError("não usado");
        }

        @Override
        public FlowStep mcpAgent(Request r) {
            throw new AssertionError("não usado");
        }

        @Override
        public FlowStep knowledgeBase(Request r, boolean rag, AIComponent generator, String problem) {
            throw new AssertionError("não usado");
        }
    }
}
