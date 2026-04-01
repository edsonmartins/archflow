package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationalAgent")
class ConversationalAgentTest {

    private ConversationalAgent agent;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        agent = ConversationalAgent.builder()
                .escalationThreshold(0.5)
                .maxContextMessages(10)
                .intent("refund", "Process refund requests")
                .intent("shipping", "Shipping and delivery inquiries")
                .build();
        context = mock(ExecutionContext.class);
    }

    @Test
    @DisplayName("should classify intent from request")
    void shouldClassifyIntentFromRequest() {
        Analysis analysis = agent.analyzeRequest("I have a problem with my order, it's broken");

        assertThat(analysis.intent()).isEqualTo("complaint");
        assertThat(analysis.confidence()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("should generate response for known intent")
    void shouldGenerateResponseForKnownIntent() {
        Response response = agent.generateResponse("Hello, good morning!");

        assertThat(response.content()).isNotBlank();
        assertThat(response.metadata()).containsKey("intent");
        assertThat(response.metadata().get("intent")).isEqualTo("greeting");
    }

    @Test
    @DisplayName("should escalate when confidence is below threshold")
    void shouldEscalateWhenConfidenceBelowThreshold() {
        ConversationalAgent strictAgent = ConversationalAgent.builder()
                .escalationThreshold(0.99)
                .build();

        Response response = strictAgent.generateResponse("xyzzy foobar gibberish");

        assertThat(response.content()).contains("human agent");
        assertThat(response.metadata()).containsEntry("escalated", true);
        assertThat(response.actions()).anyMatch(a -> a.type().equals("escalate"));
    }

    @Test
    @DisplayName("should track conversation context")
    void shouldTrackConversationContext() {
        agent.analyzeRequest("Hello there");
        agent.analyzeRequest("I need help with shipping");
        agent.analyzeRequest("When will my package arrive?");

        List<String> ctx = agent.getConversationContext();

        assertThat(ctx).hasSize(3);
        assertThat(ctx.get(0)).isEqualTo("Hello there");
        assertThat(ctx.get(2)).isEqualTo("When will my package arrive?");
    }

    @Test
    @DisplayName("should return analysis with entities")
    void shouldReturnAnalysisWithEntities() {
        Analysis analysis = agent.analyzeRequest("I have a problem with my delivery, it's not working");

        assertThat(analysis.entities()).isNotEmpty();
        assertThat(analysis.entities()).containsKey("topic");
        assertThat(analysis.entities().get("topic")).isEqualTo("delivery");
    }

    @Test
    @DisplayName("should execute task based on intent")
    void shouldExecuteTaskBasedOnIntent() {
        Task task = Task.of("conversation", Map.of("request", "Hello, I want to buy something"));

        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.output();
        assertThat(output.get("task_type")).isEqualTo("conversation");
        assertThat(output).containsKey("intent");
        assertThat(output).containsKey("response");
        assertThat(output.get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("should make decision with alternatives")
    void shouldMakeDecisionWithAlternatives() {
        agent.analyzeRequest("I want to buy a product, how much does it cost?");

        Decision decision = agent.makeDecision(context);

        assertThat(decision.action()).isNotBlank();
        assertThat(decision.reasoning()).isNotBlank();
        assertThat(decision.alternatives()).isNotEmpty();
        assertThat(decision.confidence()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("should plan actions for multi-step request")
    void shouldPlanActionsForMultiStepRequest() {
        Goal goal = Goal.of("Resolve customer shipping complaint",
                "Identify issue", "Provide resolution", "Confirm satisfaction");

        List<Action> actions = agent.planActions(goal, context);

        assertThat(actions).hasSize(5);
        assertThat(actions.get(0).type()).isEqualTo("classify_intent");
        assertThat(actions.get(1).type()).isEqualTo("extract_entities");
        assertThat(actions.get(2).type()).isEqualTo("route_request");
        assertThat(actions.get(3).type()).isEqualTo("generate_response");
        assertThat(actions.get(4).type()).isEqualTo("evaluate_satisfaction");
    }

    @Test
    @DisplayName("should handle unknown intent gracefully")
    void shouldHandleUnknownIntentGracefully() {
        Analysis analysis = agent.analyzeRequest("asdfghjkl qwerty zxcvbn");

        assertThat(analysis.intent()).isEqualTo("unknown");
        assertThat(analysis.confidence()).isLessThan(0.5);
        assertThat(analysis.suggestedActions()).contains("ask_clarification");
    }

    @Test
    @DisplayName("should use custom intent classifier")
    void shouldUseCustomIntentClassifier() {
        ConversationalAgent customAgent = ConversationalAgent.builder()
                .intentClassifier((request, intents) -> new Analysis(
                        "custom_intent",
                        Map.of("custom_entity", "value"),
                        0.99,
                        List.of("custom_action")
                ))
                .build();

        Analysis analysis = customAgent.analyzeRequest("anything at all");

        assertThat(analysis.intent()).isEqualTo("custom_intent");
        assertThat(analysis.confidence()).isEqualTo(0.99);
        assertThat(analysis.entities()).containsEntry("custom_entity", "value");
        assertThat(analysis.suggestedActions()).containsExactly("custom_action");
    }
}
