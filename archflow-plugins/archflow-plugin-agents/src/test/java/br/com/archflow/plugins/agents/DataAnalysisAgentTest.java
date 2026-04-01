package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DataAnalysisAgent")
class DataAnalysisAgentTest {

    private DataAnalysisAgent agent;
    private ExecutionContext context;
    private DataAnalysisAgent.DataSource dataSource;
    private DataAnalysisAgent.SchemaIntrospector schemaIntrospector;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataAnalysisAgent.DataSource.class);
        schemaIntrospector = mock(DataAnalysisAgent.SchemaIntrospector.class);
        context = mock(ExecutionContext.class);

        agent = DataAnalysisAgent.builder()
                .dataSource(dataSource)
                .schemaIntrospector(schemaIntrospector)
                .allowedTables(Set.of("orders", "customers", "products"))
                .maxRows(500)
                .build();
    }

    @Test
    @DisplayName("should analyze data query request")
    void shouldAnalyzeDataQueryRequest() {
        Map<String, Object> analysis = agent.analyzeRequest(
                "SELECT name, total FROM orders WHERE total > 100");

        assertThat(analysis.get("analysisType")).isEqualTo("QUERY");

        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) analysis.get("tables");
        assertThat(tables).contains("orders");

        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) analysis.get("columns");
        assertThat(columns).isNotEmpty();
    }

    @Test
    @DisplayName("should generate SQL-like query from natural language")
    void shouldGenerateSqlFromNaturalLanguage() {
        var task = Task.of("query", Map.of("query", "SELECT total FROM orders"));

        when(dataSource.executeQuery(anyString()))
                .thenReturn(List.of(Map.of("total", 150), Map.of("total", 200)));
        when(schemaIntrospector.getTables()).thenReturn(List.of("orders"));
        when(schemaIntrospector.getColumns("orders")).thenReturn(List.of("id", "total", "date"));

        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertThat(output.get("generated_sql")).isNotNull();
        assertThat((String) output.get("generated_sql")).contains("SELECT");
        assertThat(output.get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("should execute task with data source")
    void shouldExecuteTaskWithDataSource() {
        var task = Task.of("query", Map.of("query", "SELECT id FROM customers"));

        List<Map<String, Object>> mockRows = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob")
        );
        when(dataSource.executeQuery(anyString())).thenReturn(mockRows);
        when(schemaIntrospector.getTables()).thenReturn(List.of("customers"));
        when(schemaIntrospector.getColumns("customers")).thenReturn(List.of("id", "name"));

        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertThat(output.get("row_count")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) output.get("rows");
        assertThat(rows).hasSize(2);

        verify(dataSource).executeQuery(anyString());
    }

    @Test
    @DisplayName("should respect allowed tables filter")
    void shouldRespectAllowedTablesFilter() {
        var task = Task.of("query", Map.of("query", "SELECT secret FROM secret_data"));

        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isFalse();
        assertThat(result.messages()).anyMatch(m -> m.contains("Access denied"));
    }

    @Test
    @DisplayName("should handle schema introspection")
    void shouldHandleSchemaIntrospection() {
        when(schemaIntrospector.getTables()).thenReturn(List.of("orders", "customers", "products"));
        when(schemaIntrospector.getColumns("orders")).thenReturn(List.of("id", "total", "date"));
        when(schemaIntrospector.getColumns("customers")).thenReturn(List.of("id", "name", "email"));
        when(schemaIntrospector.getColumns("products")).thenReturn(List.of("id", "name", "price"));

        var task = Task.of("query", Map.of("query", "SELECT total FROM orders"));
        when(dataSource.executeQuery(anyString())).thenReturn(List.of(Map.of("total", 100)));

        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();
        verify(schemaIntrospector).getTables();
        verify(schemaIntrospector).getColumns("orders");
    }

    @Test
    @DisplayName("should make decision on analysis approach")
    void shouldMakeDecisionOnAnalysisApproach() {
        Decision decision = agent.makeDecision(context);

        assertThat(decision.action()).isEqualTo("full_analysis");
        assertThat(decision.confidence()).isGreaterThan(0.5);
        assertThat(decision.alternatives()).isNotEmpty();

        // Agent without data source should request one
        DataAnalysisAgent noSourceAgent = DataAnalysisAgent.builder().build();
        Decision noSourceDecision = noSourceAgent.makeDecision(context);
        assertThat(noSourceDecision.action()).isEqualTo("request_datasource");
    }

    @Test
    @DisplayName("should plan multi-step analysis")
    void shouldPlanMultiStepAnalysis() {
        var goal = Goal.of("Analyze quarterly sales trends", "Identify top products", "Forecast next quarter");

        List<Action> actions = agent.planActions(goal, context);

        assertThat(actions).hasSize(6);
        assertThat(actions.get(0).type()).isEqualTo("analyze_request");
        assertThat(actions.get(1).type()).isEqualTo("introspect_schema");
        assertThat(actions.get(2).type()).isEqualTo("generate_query");
        assertThat(actions.get(3).type()).isEqualTo("execute_query");
        assertThat(actions.get(4).type()).isEqualTo("analyze_results");
        assertThat(actions.get(5).type()).isEqualTo("generate_response");
    }

    @Test
    @DisplayName("should handle empty result sets gracefully")
    void shouldHandleEmptyResultSetsGracefully() {
        var task = Task.of("query", Map.of("query", "SELECT id FROM orders"));

        when(dataSource.executeQuery(anyString())).thenReturn(List.of());
        when(schemaIntrospector.getTables()).thenReturn(List.of("orders"));
        when(schemaIntrospector.getColumns("orders")).thenReturn(List.of("id"));

        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertThat(output.get("row_count")).isEqualTo(0);
        assertThat((String) output.get("response")).contains("no results");
    }
}
