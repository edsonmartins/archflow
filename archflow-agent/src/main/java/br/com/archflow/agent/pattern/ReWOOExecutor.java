package br.com.archflow.agent.pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReWOO (Reasoning Without Observation) executor.
 *
 * <p>Plans all tool calls upfront in a single LLM call, using placeholders
 * (#E1, #E2, ...) for intermediate results. Then executes all tools and
 * combines results in a final synthesis step.
 *
 * <p>Only 2 LLM calls regardless of number of tools — up to 82% token
 * reduction versus ReAct for multi-tool tasks.
 */
public class ReWOOExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReWOOExecutor.class);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("#E(\\d+)");

    private final Function<String, List<ToolPlan>> plannerFunction;
    private final BiFunction<String, Map<String, Object>, String> toolExecutor;
    private final BiFunction<String, Map<String, String>, String> synthesizer;

    private ReWOOExecutor(Builder builder) {
        this.plannerFunction = Objects.requireNonNull(builder.plannerFunction);
        this.toolExecutor = Objects.requireNonNull(builder.toolExecutor);
        this.synthesizer = Objects.requireNonNull(builder.synthesizer);
    }

    public static Builder builder() { return new Builder(); }

    public ReWOOResult execute(String query) {
        // Step 1: Plan all tool calls (single LLM call)
        log.info("ReWOO planning for: {}", query);
        List<ToolPlan> plans = plannerFunction.apply(query);
        if (plans == null || plans.isEmpty()) {
            String answer = synthesizer.apply(query, Map.of());
            return new ReWOOResult(answer, List.of(), Map.of());
        }
        log.debug("Planned {} tool calls", plans.size());

        // Step 2: Execute tools, resolving placeholders from earlier results
        Map<String, String> evidences = new LinkedHashMap<>();
        for (ToolPlan plan : plans) {
            Map<String, Object> resolvedArgs = resolvePlaceholders(plan.arguments(), evidences);
            log.debug("Executing tool: {} ({})", plan.toolName(), plan.evidenceKey());

            try {
                String result = toolExecutor.apply(plan.toolName(), resolvedArgs);
                evidences.put(plan.evidenceKey(), result);
            } catch (Exception e) {
                log.error("Tool execution failed: {}", plan.toolName(), e);
                evidences.put(plan.evidenceKey(), "ERROR: " + e.getMessage());
            }
        }

        // Step 3: Synthesize final answer (single LLM call)
        log.debug("Synthesizing final answer from {} evidences", evidences.size());
        String answer = synthesizer.apply(query, evidences);

        return new ReWOOResult(answer, plans, evidences);
    }

    private Map<String, Object> resolvePlaceholders(Map<String, Object> args, Map<String, String> evidences) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getValue() instanceof String strVal) {
                Matcher matcher = PLACEHOLDER_PATTERN.matcher(strVal);
                String result = strVal;
                while (matcher.find()) {
                    String key = "#E" + matcher.group(1);
                    String replacement = evidences.getOrDefault(key, null);
                    if (replacement == null) {
                        log.warn("Unresolved placeholder: {}", key);
                        replacement = key;
                    }
                    result = result.replace(key, replacement);
                }
                resolved.put(entry.getKey(), result);
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    // Inner types
    public record ToolPlan(String evidenceKey, String toolName, Map<String, Object> arguments) {
        public ToolPlan { if (arguments == null) arguments = Map.of(); }
    }

    public record ReWOOResult(String answer, List<ToolPlan> plans, Map<String, String> evidences) {}

    public static class Builder {
        private Function<String, List<ToolPlan>> plannerFunction;
        private BiFunction<String, Map<String, Object>, String> toolExecutor;
        private BiFunction<String, Map<String, String>, String> synthesizer;

        public Builder plannerFunction(Function<String, List<ToolPlan>> fn) { this.plannerFunction = fn; return this; }
        public Builder toolExecutor(BiFunction<String, Map<String, Object>, String> fn) { this.toolExecutor = fn; return this; }
        public Builder synthesizer(BiFunction<String, Map<String, String>, String> fn) { this.synthesizer = fn; return this; }
        public ReWOOExecutor build() { return new ReWOOExecutor(this); }
    }
}
