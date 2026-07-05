package br.com.archflow.agent.orchestration;

import br.com.archflow.conversation.agent.ConversationalAgent.ChatFunction;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.PlanSpec;
import br.com.archflow.orchestration.Planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Model-driven {@link Planner} that decomposes a goal into subtask strings via
 * the same {@link ChatFunction} LLM seam the {@code ConversationalAgent} uses —
 * so the same wired model (resolved by {@code LLMConfigResolver}) serves both,
 * and this stays unit-testable with a scripted chat function (ADR-0002 D4).
 *
 * <p>The model is asked for one subtask per line; the response is parsed
 * defensively (bullets/numbering stripped, blanks and code fences dropped) and
 * capped to {@link PlanSpec#maxItems()}.
 */
public final class LlmPlanner implements Planner<String> {

    private final ChatFunction chat;

    public LlmPlanner(ChatFunction chat) {
        this.chat = chat;
    }

    @Override
    public List<String> decompose(Goal goal, PlanSpec spec) {
        String response = chat.reply(buildPrompt(goal, spec));
        return parse(response, spec.maxItems());
    }

    private static String buildPrompt(Goal goal, PlanSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.decomposePrompt().strip()).append("\n\n");
        sb.append("Goal: ").append(goal.description()).append('\n');
        Map<String, Object> inputs = goal.inputs();
        if (inputs != null && !inputs.isEmpty()) {
            sb.append("Context:\n");
            inputs.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        }
        sb.append("\nReturn at most ").append(spec.maxItems())
          .append(" subtasks, one per line, with no numbering and no extra commentary.");
        return sb.toString();
    }

    /** Parses an LLM list response into clean subtask lines. */
    static List<String> parse(String response, int maxItems) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String raw : response.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("```")) {
                continue;
            }
            // Strip a leading "- ", "* ", "• " or "1. " / "2) " marker.
            line = line.replaceFirst("^(?:[-*•]\\s+|\\d+[.)]\\s+)", "").strip();
            if (line.isEmpty()) {
                continue;
            }
            items.add(line);
            if (items.size() >= maxItems) {
                break;
            }
        }
        return items;
    }
}
