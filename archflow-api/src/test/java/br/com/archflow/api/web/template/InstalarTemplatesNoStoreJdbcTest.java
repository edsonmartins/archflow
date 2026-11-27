package br.com.archflow.api.web.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.NodeSupport;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.impl.TemplateControllerImpl;
import br.com.archflow.api.web.workflow.JdbcWorkflowRuntimeStore;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.template.WorkflowTemplateRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * RCHFLW-200: {@code POST /api/templates/{id}/install} dos quatro templates SPI no store JDBC — o que roda em
 * produção. Antes, knowledge-base, agent-supervisor e document-processing davam 500 (um {@code Instant} cru no
 * {@code configuration.createdAt}, que o mapper do store não serializava) e, sem os parâmetros que têm default,
 * 400. O teste de conformidade usa o store em memória, que guarda o mapa sem serializar — por isso não pegou.
 *
 * <p>H2 em modo PostgreSQL com o DDL da tabela que o install grava (V6_3 + coluna da V6_12_2), como o
 * {@code JdbcVersoesEmProducaoTest}: o teste com o Postgres de verdade precisa de Docker.</p>
 */
@DisplayName("POST /install dos templates SPI no store JDBC (RCHFLW-200)")
class InstalarTemplatesNoStoreJdbcTest {

    private JdbcWorkflowRuntimeStore store;
    private SpringTemplateController web;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:install-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE workflow_documents (
                        id VARCHAR(64) PRIMARY KEY, document TEXT NOT NULL, updated_at TIMESTAMP NOT NULL,
                        tenant_id VARCHAR(64) NOT NULL)
                    """);
        }
        store = new JdbcWorkflowRuntimeStore(ds);

        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
        web = new SpringTemplateController(new TemplateControllerImpl(WorkflowTemplateRegistry.getInstance()),
                store, new NodeSupport(factory));
    }

    /**
     * Só os obrigatórios sem default — vectorStoreId e knowledgeBaseId não têm valor que o template possa
     * inventar. Todo o resto (inclusive os enumParameter, required com default) fica de fora.
     */
    static Stream<Arguments> minimos() {
        return Stream.of(
                Arguments.of("customer-support", Map.of("knowledgeBaseId", "docs-sac")),
                Arguments.of("knowledge-base", Map.of("vectorStoreId", "kb-1")),
                Arguments.of("document-processing", Map.of()),
                Arguments.of("agent-supervisor", Map.of()));
    }

    static Stream<Arguments> explicitos() {
        return Stream.of(
                Arguments.of("customer-support",
                        Map.of("knowledgeBaseId", "docs-sac", "responseLanguage", "pt", "maxIterations", 4)),
                Arguments.of("knowledge-base",
                        Map.of("vectorStoreId", "kb-1", "answerStyle", "detailed", "topK", 3)),
                Arguments.of("document-processing",
                        Map.of("outputFormat", "csv", "enableOcr", false)),
                Arguments.of("agent-supervisor",
                        Map.of("agents", List.of("a", "b"), "aggregationMethod", "voting", "maxIterations", 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("minimos")
    @DisplayName("sem os parâmetros que têm default: 201 e o rascunho volta do banco")
    void instalaComDefaults(String id, Map<String, Object> params) {
        Map<String, Object> stored = instalaELe(id, params);

        assertThat(stored.get("template")).isEqualTo(id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("explicitos")
    @DisplayName("com parâmetros explícitos: 201 e o rascunho volta do banco")
    void instalaComParametros(String id, Map<String, Object> params) {
        Map<String, Object> stored = instalaELe(id, params);

        assertThat(stored.get("template")).isEqualTo(id);
    }

    @Test
    @DisplayName("agent-supervisor sem parâmetros grava o método e os agentes da definição")
    void supervisorUsaDefaultsDaDefinicao() {
        Map<String, Object> stored = instalaELe("agent-supervisor", Map.of());

        Map<?, ?> aggregator = config(stored, "aggregator");
        Map<?, ?> supervisor = config(stored, "supervisor");
        assertThat(aggregator.get("method")).isEqualTo("synthesis");
        assertThat(supervisor.get("agents")).isEqualTo(List.of("researcher", "writer", "reviewer"));
    }

    @Test
    @DisplayName("o mapper do store serializa java.time: um Instant no documento não derruba o put")
    void storeSerializaInstant() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "wf-instant");
        doc.put("configuration", Map.of("createdAt", Instant.parse("2026-11-27T12:00:00Z")));

        assertThatCode(() -> store.putWorkflow(DefaultTenant.ID, "wf-instant", doc)).doesNotThrowAnyException();
        assertThat(store.getWorkflow(DefaultTenant.ID, "wf-instant")).containsKey("configuration");
    }

    private Map<String, Object> instalaELe(String id, Map<String, Object> params) {
        ResponseEntity<Map<String, Object>> created =
                web.installTemplate(id, new InstallTemplateRequest("Instalado " + id, params));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = (String) created.getBody().get("workflowId");
        Map<String, Object> stored = store.getWorkflow(DefaultTenant.ID, workflowId);
        assertThat(stored).as("o rascunho tem de estar no banco").isNotNull();
        assertThat((List<?>) stored.get("steps")).isNotEmpty();
        // O createdAt do template chega como texto ISO-8601, não como o número de um Instant serializado.
        Map<?, ?> configuration = (Map<?, ?>) stored.get("configuration");
        assertThat(configuration.get("createdAt")).isInstanceOf(String.class);
        assertThatCode(() -> Instant.parse((String) configuration.get("createdAt"))).doesNotThrowAnyException();
        return stored;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> config(Map<String, Object> stored, String stepId) {
        return ((List<Map<String, Object>>) stored.get("steps")).stream()
                .filter(s -> stepId.equals(s.get("id")))
                .map(s -> (Map<?, ?>) s.get("configuration"))
                .findFirst().orElseThrow();
    }
}
