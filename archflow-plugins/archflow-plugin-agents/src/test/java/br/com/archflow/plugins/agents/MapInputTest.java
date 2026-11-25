package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.domain.Analysis;
import br.com.archflow.model.ai.domain.Response;
import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O Executar do designer e a fila de invocações entregam um mapa ({@code {"message": ...}}); os agentes
 * faziam {@code (String) input} / {@code (Task) input} e quebravam com {@code ClassCastException}.
 */
@DisplayName("Agentes aceitam a entrada como mapa ou texto")
class MapInputTest {

    private static final Map<String, Object> ENTRADA = Map.of("message", "can you help me", "sessionId", "s-1");
    private final ExecutionContext context = mock(ExecutionContext.class);

    private static <T extends br.com.archflow.model.ai.AIComponent> T inicializado(T component) {
        component.initialize(Map.of());
        return component;
    }

    @Test
    void conversationalAgentLeOTextoDoMapa() throws Exception {
        ConversationalAgent agent = inicializado(new ConversationalAgent());

        Analysis analysis = (Analysis) agent.execute("analyzeRequest", ENTRADA, context);
        Response response = (Response) agent.execute("generateResponse", ENTRADA, context);
        Result result = (Result) agent.execute("executeTask", ENTRADA, context);

        assertThat(analysis.intent()).isEqualTo("support");
        assertThat(response.metadata()).containsEntry("intent", "support");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).asString().contains("support");
        assertThat(agent.execute("planActions", "atender bem", context)).asList().isNotEmpty();
    }

    @Test
    void researchAgentRecebeTextoComoTarefa() throws Exception {
        ResearchAgent agent = inicializado(new ResearchAgent());

        Result result = (Result) agent.execute("executeTask", ENTRADA, context);
        Result compare = (Result) agent.execute("executeTask", Map.of("type", "compare", "query", "a vs b"), context);

        assertThat(result.success()).isTrue();
        assertThat(compare.success()).isTrue();
        assertThat(compare.output()).asString().contains("compare");
        assertThat(agent.execute("planActions", Map.of("description", "d", "successCriteria", List.of("c")),
                context)).asList().isNotEmpty();
    }

    @Test
    void dataAnalysisAgentRecebeMapa() throws Exception {
        DataAnalysisAgent agent = inicializado(new DataAnalysisAgent());

        Result result = (Result) agent.execute("executeTask", Map.of("message", "average price per month"), context);
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) agent.execute("analyzeRequest",
                Map.of("message", "select total from orders"), context);

        assertThat(result.success()).isTrue();
        assertThat(analysis.get("tables")).asList().contains("orders");
    }

    @Test
    void monitoringAgentRecebeMapa() throws Exception {
        MonitoringAgent agent = inicializado(new MonitoringAgent());

        Result result = (Result) agent.execute("executeTask", ENTRADA, context);
        Result query = (Result) agent.execute("analyzeRequest", Map.of("query", "system health"), context);

        assertThat(result.success()).isTrue();
        assertThat(query.success()).isTrue();
    }
}
