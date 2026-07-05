package br.com.archflow.api.agui;

import java.util.List;
import java.util.Map;

/**
 * AG-UI {@code RunAgentInput} (design-0006): the request body a client POSTs to
 * start a run. All fields optional for the workflow bridge — {@code state}/
 * {@code messages} seed the flow input, {@code runId}/{@code threadId} identify
 * the run/conversation.
 */
public record RunAgentInput(
        List<Map<String, Object>> messages,
        Map<String, Object> state,
        List<Map<String, Object>> tools,
        List<Map<String, Object>> context,
        String threadId,
        String runId) {
}
