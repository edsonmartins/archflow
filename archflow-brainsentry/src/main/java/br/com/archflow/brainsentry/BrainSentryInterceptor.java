package br.com.archflow.brainsentry;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool interceptor that enriches prompts via Brain Sentry before execution.
 *
 * <p>Sits in the archflow ToolInterceptorChain and:
 * <ul>
 *   <li><b>Before execution</b>: Calls Brain Sentry /v1/intercept to enrich the
 *       tool input with relevant memories and context</li>
 *   <li><b>After execution</b>: Optionally captures the result as a new memory</li>
 * </ul>
 *
 * <p>Order: 5 (runs early, before guardrails at 10)
 */
public class BrainSentryInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BrainSentryInterceptor.class);
    private static final String ENRICHED_ATTR = "brainsentry.enriched";

    private final BrainSentryClient client;
    private final boolean captureResults;

    /**
     * Creates an interceptor.
     *
     * @param client The Brain Sentry client
     * @param captureResults Whether to capture tool results as memories
     */
    public BrainSentryInterceptor(BrainSentryClient client, boolean captureResults) {
        this.client = client;
        this.captureResults = captureResults;
    }

    public BrainSentryInterceptor(BrainSentryClient client) {
        this(client, false);
    }

    @Override
    public void beforeExecute(ToolContext context) {
        if (context.getInput() instanceof String input) {
            try {
                EnrichedPrompt enriched = client.intercept(input, 2000);
                if (enriched.enhanced()) {
                    context.setInput(enriched.fullPrompt());
                    context.setAttribute(ENRICHED_ATTR, enriched);
                    log.debug("Enriched input for tool {} with {} memories",
                            context.getToolName(), enriched.memoriesUsed().size());
                }
            } catch (Exception e) {
                log.warn("Brain Sentry interception failed, proceeding with original input", e);
            }
        }
    }

    @Override
    public ToolResult afterExecute(ToolContext context, ToolResult result) {
        if (captureResults && result != null && result.isSuccess()) {
            try {
                String content = "Tool %s executed successfully: %s".formatted(
                        context.getToolName(), truncate(String.valueOf(result.getData()), 500));
                client.createMemory(content, "ACTION", "MINOR", "EPISODIC",
                        java.util.List.of("tool-result", context.getToolName()));
                log.debug("Captured tool result as memory for {}", context.getToolName());
            } catch (Exception e) {
                log.warn("Failed to capture tool result as memory", e);
            }
        }
        return result;
    }

    @Override
    public int order() {
        return 5; // Before guardrails (10)
    }

    @Override
    public String getName() {
        return "BrainSentryInterceptor";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
