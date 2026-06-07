package br.com.archflow.agent.orchestration;

import br.com.archflow.conversation.agent.ConversationalAgent.ChatFunction;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.PlanSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPlannerTest {

    @Test
    void parsesNumberedAndBulletedListsStrippingMarkers() {
        ChatFunction chat = prompt -> "1. Audit auth.js\n2) Audit db.js\n- Check logging\n* Review CORS";
        LlmPlanner planner = new LlmPlanner(chat);

        List<String> items = planner.decompose(Goal.of("audit secrets"), new PlanSpec("Liste os arquivos", 10));

        assertThat(items).containsExactly("Audit auth.js", "Audit db.js", "Check logging", "Review CORS");
    }

    @Test
    void dropsBlankLinesAndCodeFences() {
        ChatFunction chat = prompt -> "```\nFirst task\n\n  \nSecond task\n```";
        LlmPlanner planner = new LlmPlanner(chat);

        List<String> items = planner.decompose(Goal.of("g"), new PlanSpec("p", 10));

        assertThat(items).containsExactly("First task", "Second task");
    }

    @Test
    void capsToMaxItems() {
        ChatFunction chat = prompt -> "a\nb\nc\nd\ne";
        LlmPlanner planner = new LlmPlanner(chat);

        List<String> items = planner.decompose(Goal.of("g"), new PlanSpec("p", 3));

        assertThat(items).containsExactly("a", "b", "c");
    }

    @Test
    void emptyResponseYieldsNoItems() {
        LlmPlanner planner = new LlmPlanner(prompt -> "   ");
        assertThat(planner.decompose(Goal.of("g"), new PlanSpec("p", 5))).isEmpty();
    }

    @Test
    void promptCarriesDecompositionInstructionGoalAndContext() {
        AtomicReference<String> seen = new AtomicReference<>();
        ChatFunction chat = prompt -> {
            seen.set(prompt);
            return "task";
        };
        LlmPlanner planner = new LlmPlanner(chat);

        planner.decompose(new Goal("classify tickets", java.util.Map.of("tenant", "acme")),
                new PlanSpec("Break the goal into steps", 4));

        assertThat(seen.get())
                .contains("Break the goal into steps")
                .contains("Goal: classify tickets")
                .contains("tenant: acme")
                .contains("at most 4 subtasks");
    }
}
