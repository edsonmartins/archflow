package br.com.archflow.plugins.assistants;

import br.com.archflow.model.ai.domain.Analysis;
import br.com.archflow.model.ai.domain.Response;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O {@code analyze} recebia o mapa do Executar com {@code (String) input}; o {@code respond}, a análise que
 * volta como mapa de um checkpoint, com {@code (Analysis) input}.
 */
@DisplayName("TechSupportAssistant aceita mapa e texto")
class MapInputTest {

    private final ExecutionContext context = mock(ExecutionContext.class);

    private TechSupportAssistant assistant() {
        TechSupportAssistant assistant = new TechSupportAssistant();
        assistant.initialize(Map.of());
        return assistant;
    }

    @Test
    void analyzeLeOTextoDoMapa() throws Exception {
        Analysis analysis = (Analysis) assistant().execute("analyze",
                Map.of("message", "I forgot my password", "sessionId", "s-1"), context);

        assertThat(analysis.intent()).isEqualTo("password_reset");
    }

    @Test
    void respondAceitaAnaliseComoMapaETextoCru() throws Exception {
        Response fromMap = (Response) assistant().execute("respond", Map.of("intent", "connectivity",
                "entities", Map.of(), "confidence", 0.6, "suggestedActions", List.of("provide_solution")), context);
        Response fromText = (Response) assistant().execute("respond",
                Map.of("message", "my wifi is offline"), context);

        assertThat(fromMap.metadata()).containsEntry("intent", "connectivity");
        assertThat(fromText.metadata()).containsEntry("intent", "connectivity");
    }
}
