package br.com.archflow.api.flow;

import br.com.archflow.model.tenant.DefaultTenant;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.dto.TemplateResponse;
import br.com.archflow.api.template.impl.TemplateControllerImpl;
import br.com.archflow.api.web.template.SpringTemplateController;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.template.WorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateConnection;
import br.com.archflow.template.WorkflowTemplateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Os templates do backend chegam com o grafo real e o motor de verdade o lê (RCHFLW-18).
 *
 * <p>Antes, {@code GET /api/templates} só mandava {@code stepCount} e {@code /install} gravava
 * {@code steps: []}: o clone de qualquer template SPI nascia vazio. Como no
 * {@link DslConformanceTest}, a forma não é conferida contra um JSON escrito à mão — o documento
 * passa pelo {@link DefaultWorkflowDeserializer} real e pela ponte YAML (o terceiro leitor,
 * {@code SerializableStep}). Se um dos leitores mudar como lê o documento, este teste quebra.
 *
 * <p>A outra metade é a honestidade: knowledge-base, document-processing e agent-supervisor ainda usam
 * tipos de nó sem executor (esperam a decisão do RCHFLW-205). O documento vem inteiro, e
 * {@code unsupportedNodeTypes} diz quais nós falhariam com "component not found". O customer-support foi
 * refeito com nós que o motor executa (RCHFLW-203): instalado com a base, a lista é vazia.
 */
@DisplayName("templates do backend → motor: grafo real, nós sem executor marcados")
class TemplateConformanceTest {

    private static final ComponentCatalog EMPTY_CATALOG = emptyCatalog();

    private final DefaultFlowStepFactory factory = factoryWith(EMPTY_CATALOG);
    private final WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(factory);
    private final TemplateControllerImpl controller =
            new TemplateControllerImpl(WorkflowTemplateRegistry.getInstance());

    private static ComponentCatalog emptyCatalog() {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        return catalog;
    }

    /**
     * O motor como o servidor o tem: catálogo vazio e o LLM padrão da plataforma ({@code archflow.llm.provider}),
     * de onde o LLM Chat sem provider no nó herda o dele — sem o padrão, todo llm-chat seria "sem executor".
     */
    private static DefaultFlowStepFactory factoryWith(ComponentCatalog catalog) {
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
        factory.setPlatformDefaultLLM(ResolvedLLMConfig.builder().provider("openrouter").model("modelo-padrao")
                .build());
        return factory;
    }

    private static Map<String, Object> document(String id, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("metadata", Map.of("name", id, "version", "1.0.0"));
        doc.put("configuration", Map.of());
        doc.put("steps", steps);
        return doc;
    }

    private static WorkflowTemplate template(String id) {
        return WorkflowTemplateRegistry.getInstance().getTemplate(id).orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"customer-support", "knowledge-base", "document-processing", "agent-supervisor"})
    @DisplayName("GET /api/templates/{id} traz os passos e o motor os lê com todas as arestas")
    void templateStepsRunThroughTheRealDeserializer(String id) {
        TemplateResponse response = controller.getTemplate(id);
        assertThat(response.steps())
                .as("sem passos o clone nasce vazio (RCHFLW-18)")
                .hasSize(response.stepCount())
                .isNotEmpty();

        Flow flow = deserializer.toFlow(document(id, response.steps()));

        assertThat(flow.getSteps()).hasSize(response.stepCount());
        List<TemplateConnection> declared =
                List.copyOf(template(id).getDefinition().getStructure().getConnections().values());
        int edges = flow.getSteps().stream().mapToInt(s -> s.getConnections().size()).sum();
        assertThat(edges)
                .as("aresta no topo do documento não chega ao motor — tem de estar em steps[].connections")
                .isEqualTo(declared.size());

        ConditionEvaluator evaluator = new ConditionEvaluator();
        for (TemplateConnection c : declared) {
            FlowStep source = flow.getSteps().stream().filter(s -> s.getId().equals(c.getSource()))
                    .findFirst().orElseThrow();
            assertThat(source.getConnections()).extracting(StepConnection::getTargetId).contains(c.getTarget());
        }
        for (FlowStep step : flow.getSteps()) {
            for (StepConnection conn : step.getConnections()) {
                conn.getCondition().ifPresent(cond -> {
                    assertThat(cond).doesNotContain("{{");
                    assertThat(evaluator.isWellFormed(cond)).as("condição %s", cond).isTrue();
                });
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"customer-support", "knowledge-base", "document-processing", "agent-supervisor"})
    @DisplayName("o documento atravessa a ponte YAML com o type cru")
    void documentSurvivesTheYamlBridge(String id) throws Exception {
        List<Map<String, Object>> steps = controller.getTemplate(id).steps();
        String json = new ObjectMapper().writeValueAsString(document(id, steps));
        WorkflowYamlBridge bridge = new WorkflowYamlBridge();

        String yaml = bridge.jsonToYaml(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = new ObjectMapper().readValue(bridge.yamlToJson(yaml), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backSteps = (List<Map<String, Object>>) back.get("steps");
        assertThat(backSteps).extracting(s -> s.get("type"))
                .as("o roteamento de adapter casa pelo type cru")
                .containsExactlyElementsOf(steps.stream().map(s -> s.get("type")).toList());
        // As arestas e as condições convertidas também atravessam (SerializableStep/SerializableConnection):
        // sem isto o YAML de um workflow instalado podia perder os ramos sem nenhum teste notar.
        assertThat(edgesWithConditions(deserializer.toFlow(back)))
                .as("arestas e condições depois do JSON → YAML → JSON")
                .isEqualTo(edgesWithConditions(deserializer.toFlow(document(id, steps))))
                .isNotEmpty();
    }

    /** {@code origem->destino [condição]} de cada aresta do fluxo, na ordem dos passos. */
    private static List<String> edgesWithConditions(Flow flow) {
        return flow.getSteps().stream()
                .flatMap(s -> s.getConnections().stream()
                        .map(c -> s.getId() + "->" + c.getTargetId() + " [" + c.getCondition().orElse("") + "]"))
                .toList();
    }

    @Test
    @DisplayName("os nós sem executor são listados; os que o motor resolve, não")
    void unsupportedNodeTypesFollowTheRuntime() {
        List<Map<String, Object>> steps = controller.getTemplate("knowledge-base").steps();

        List<String> none = new NodeSupport(factory).unsupportedNodeTypes(steps);
        // Entrada e Saída executam como passagem (RCHFLW-11): não são mais marcadas.
        assertThat(none).contains("llm", "reranker").doesNotContain("input", "output");

        ComponentCatalog withLlm = mock(ComponentCatalog.class);
        when(withLlm.getComponent(anyString())).thenReturn(Optional.empty());
        when(withLlm.getComponent("llm")).thenReturn(Optional.of(mock(AIComponent.class)));
        assertThat(new NodeSupport(factoryWith(withLlm)).unsupportedNodeTypes(steps))
                .as("um componente registrado no catálogo deixa de ser marcado")
                .doesNotContain("llm")
                .contains("reranker");
    }

    /**
     * RCHFLW-203: o customer-support usava 'llm', 'sentiment' e 'human-handoff', que nada executa — toda execução
     * falhava no 2º passo. Refeito, com a base escolhida no install nenhum nó fica sem executor, e a prévia da galeria
     * não marca a busca cuja base o install pede.
     */
    @ParameterizedTest(name = "enableSentiment={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("customer-support instalado com a base: nenhum nó sem executor")
    void customerSupportHasNoUnsupportedNode(boolean enableSentiment) {
        Map<String, Object> params = Map.of("knowledgeBaseId", "kb-suporte", "enableSentiment", enableSentiment);
        List<Map<String, Object>> installed = controller.templateSteps("customer-support", params);

        assertThat(new NodeSupport(factory).unsupportedNodeTypes(installed)).isEmpty();
        // A prévia (GET, com os defaults) também: a base é obrigatória e sem padrão, então o nó leva o marcador que o
        // install preenche — antes ia "", a busca caía no catálogo e a galeria mandava trocar o nó (RCHFLW-203/204).
        assertThat(new NodeSupport(factory).unsupportedNodeTypes(controller.getTemplate("customer-support").steps()))
                .isEmpty();
        assertThatCode(() -> deserializer.toFlow(document("customer-support", installed)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passos dedicados do motor contam como executáveis; config inválida não é tipo ausente")
    void engineNativeStepsAreSupported() {
        NodeSupport support = new NodeSupport(factory);
        List<Map<String, Object>> steps = List.of(
                Map.of("id", "a", "type", "condition", "componentId", "condition"),
                Map.of("id", "b", "type", "switch"),
                Map.of("id", "c", "type", "APPROVAL"),
                Map.of("id", "d", "type", "ORCHESTRATE"),
                Map.of("id", "e", "type", "mcp-agent", "componentId", "mcp-agent",
                        "configuration", Map.of("systemPrompt", "oi")),
                // mcp-agent sem systemPrompt lança ao inicializar: o tipo existe, a config é que falta.
                Map.of("id", "f", "type", "mcp-agent", "componentId", "mcp-agent"),
                Map.of("id", "g", "type", "AGENT", "componentId", "agent"));

        assertThat(support.unsupportedNodeTypes(steps)).containsExactly("agent");
        assertThat(support.unsupportedNodeTypes(null)).isEmpty();
    }

    @Test
    @DisplayName("POST /install grava o grafo do template, com os parâmetros do pedido")
    void installStoresTheGraph() {
        InMemoryWorkflowRuntimeStore store = new InMemoryWorkflowRuntimeStore();
        SpringTemplateController web = new SpringTemplateController(controller, store, new NodeSupport(factory));

        ResponseEntity<Map<String, Object>> created = web.installTemplate("customer-support",
                new InstallTemplateRequest("Meu SAC", Map.of("knowledgeBaseId", "docs-sac", "responseLanguage", "pt")));

        String workflowId = (String) created.getBody().get("workflowId");
        Map<String, Object> stored = store.getWorkflow(DefaultTenant.ID, workflowId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) stored.get("steps");
        assertThat(steps).hasSize(14);
        assertThat(steps).filteredOn(s -> "kb-search".equals(s.get("id"))).singleElement()
                .extracting(s -> ((Map<?, ?>) s.get("configuration")).get("knowledgeBaseId"))
                .isEqualTo("docs-sac");
        // Com a base informada, a busca do template executa pela base de conhecimento (RCHFLW-55), e o resto do
        // grafo é de nós que o motor executa (RCHFLW-203).
        assertThat(created.getBody().get("unsupportedNodeTypes")).asList().isEmpty();
        assertThatCode(() -> deserializer.toFlow(stored)).doesNotThrowAnyException();
    }

    /**
     * RCHFLW-204: a galeria passou a instalar os templates do backend pelo /install em vez de clonar pelo
     * POST /api/workflows, que grava o CREATE do workflow. Sem o mesmo evento aqui, o fluxo criado pela galeria
     * sumia da trilha de auditoria.
     */
    @Test
    @DisplayName("POST /install grava na auditoria o CREATE do workflow criado, como o POST /api/workflows")
    void installIsAudited() {
        br.com.archflow.observability.audit.InMemoryAuditRepository audit =
                new br.com.archflow.observability.audit.InMemoryAuditRepository();
        SpringTemplateController web = new SpringTemplateController(controller,
                new InMemoryWorkflowRuntimeStore(), new NodeSupport(factory));
        web.setAuditTrail(new br.com.archflow.api.audit.AuditTrail(() -> audit));

        String workflowId = (String) web.installTemplate("customer-support",
                new InstallTemplateRequest("Meu SAC", Map.of("knowledgeBaseId", "docs-sac", "responseLanguage", "pt")))
                .getBody().get("workflowId");

        assertThat(audit.query(br.com.archflow.observability.audit.AuditRepository.AuditQuery.builder().limit(10)))
                .singleElement().satisfies(e -> {
                    assertThat(e.getAction()).isEqualTo(br.com.archflow.observability.audit.AuditAction.CREATE);
                    assertThat(e.getResourceType()).isEqualTo("workflow");
                    assertThat(e.getResourceId()).isEqualTo(workflowId);
                    assertThat(e.isSuccess()).isTrue();
                });
    }

    @Test
    @DisplayName("a API web marca os templates da lista e os do pacote da UI pela mesma regra")
    void webLayerMarksTemplates() {
        SpringTemplateController web = new SpringTemplateController(controller,
                new InMemoryWorkflowRuntimeStore(), new NodeSupport(factory));

        // Os três que esperam o RCHFLW-205 seguem marcados; o customer-support executa (RCHFLW-203) e a galeria não o
        // anuncia "sem executor" — a base que falta é a que o formulário do install pede (RCHFLW-204).
        assertThat(web.listTemplates()).filteredOn(t -> !"customer-support".equals(t.id())).allSatisfy(t ->
                assertThat(t.unsupportedNodeTypes()).as(t.id()).isNotNull().isNotEmpty());
        assertThat(web.getTemplate("customer-support").unsupportedNodeTypes()).isNotNull().isEmpty();
        assertThat(web.getTemplate("knowledge-base").unsupportedNodeTypes()).contains("reranker");

        Map<String, List<String>> bundled = web.unsupportedNodeTypes(Map.of("sac-basic", List.of(
                Map.of("id", "s2", "type", "condition", "componentId", "condition"),
                Map.of("id", "s3", "type", "agent", "componentId", "agent"))));
        assertThat(bundled).containsEntry("sac-basic", List.of("agent"));
    }
}
