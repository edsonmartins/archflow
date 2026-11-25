package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-12 de ponta a ponta: um documento do designer com nota adesiva, quadro e separador executa
 * no motor de verdade e termina COMPLETED — antes a nota derrubava a execução inteira com
 * {@code component not found: sticky-note}, mesmo com os outros passos certos.
 */
@DisplayName("anotações do canvas não derrubam a execução")
class AnotacoesNaoExecutamTest {

    @Test
    @DisplayName("fluxo com nota, quadro e separador termina COMPLETED e só os passos reais rodam")
    void fluxoComAnotacoesCompleta() throws Exception {
        WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
                new DefaultFlowStepFactory(mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                        mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                        mock(br.com.archflow.engine.core.StateManager.class), null));
        Map<String, Object> documento = Map.of(
                "id", "exec-anotacoes",
                "metadata", Map.of("name", "F1 com nota"),
                "steps", List.of(
                        Map.of("id", "nota", "componentId", "sticky-note", "type", "STICKY-NOTE",
                                "position", Map.of("x", 0, "y", 0),
                                "configuration", Map.of("text", "ramo AUTO vai para o LLM")),
                        Map.of("id", "tem-entrada", "componentId", "condition", "type", "CONDITION",
                                "configuration", Map.of("conditionExpression", "${input} != ''"),
                                "connections", List.of(Map.of("sourceId", "tem-entrada", "targetId", "rota",
                                        "branch", "true"))),
                        Map.of("id", "quadro", "componentId", "group-frame", "type", "GROUP-FRAME"),
                        Map.of("id", "rota", "componentId", "switch", "type", "SWITCH",
                                "configuration", Map.of("switchExpression", "${input}", "switchCases", "oi")),
                        Map.of("id", "divisor", "componentId", "section-divider", "type", "SECTION-DIVIDER")));

        Flow flow = deserializer.toFlow(documento);
        FlowEngine engine = FlowEngineFactory.create(new InMemoryFlowRepository(), null, null,
                new InMemoryStateManager());
        DefaultExecutionContext ctx = new DefaultExecutionContext("t1", "u1", flow.getId(),
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", "oi");

        FlowResult result = engine.execute(flow, ctx).get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(flow.getSteps()).extracting(s -> s.getId()).containsExactly("tem-entrada", "rota");
        assertThat(ctx.get("rota").map(Object::toString).orElse("")).contains("branch=oi");
    }

    /**
     * O canvas não deixa desenhar uma aresta até uma nota (ela não tem ponto de conexão), mas o copiloto
     * ({@code connectNodes}), o YAML ou a API podem gravá-la. A nota sai do fluxo; a aresta tem de sair junto,
     * senão o validador do motor recusa o fluxo inteiro com "Target step does not exist".
     */
    @Test
    @DisplayName("aresta apontando para uma nota não derruba o fluxo")
    void arestaParaNotaNaoDerruba() throws Exception {
        WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
                new DefaultFlowStepFactory(mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                        mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                        mock(br.com.archflow.engine.core.StateManager.class), null));
        Map<String, Object> documento = Map.of(
                "id", "exec-aresta-nota",
                "steps", List.of(
                        Map.of("id", "decide", "componentId", "condition", "type", "CONDITION",
                                "configuration", Map.of("conditionExpression", "true"),
                                "connections", List.of(
                                        Map.of("sourceId", "decide", "targetId", "nota"),
                                        Map.of("sourceId", "decide", "targetId", "fim", "branch", "true"))),
                        Map.of("id", "nota", "componentId", "sticky-note", "type", "STICKY-NOTE"),
                        Map.of("id", "fim", "componentId", "condition", "type", "CONDITION",
                                "configuration", Map.of("conditionExpression", "true"))));

        Flow flow = deserializer.toFlow(documento);
        FlowResult result = FlowEngineFactory.create(new InMemoryFlowRepository(), null, null,
                        new InMemoryStateManager())
                .execute(flow, new DefaultExecutionContext("t1", "u1", flow.getId(),
                        MessageWindowChatMemory.builder().maxMessages(5).build()))
                .get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(flow.getSteps().get(0).getConnections()).extracting(c -> c.getTargetId())
                .containsExactly("fim");
    }
}
