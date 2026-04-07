package br.com.archflow.agent.confidence;

import java.util.List;

public record ScoringContext(String response, List<ToolExecutionOutcome> toolResults,
                              String userQuery, int conversationLength) {
    public record ToolExecutionOutcome(String toolName, boolean success, String errorMessage) {
        public static ToolExecutionOutcome success(String toolName) { return new ToolExecutionOutcome(toolName, true, null); }
        public static ToolExecutionOutcome failure(String toolName, String error) { return new ToolExecutionOutcome(toolName, false, error); }
    }
}
