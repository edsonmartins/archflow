package br.com.archflow.api.linktor.publisher;

import br.com.archflow.api.linktor.LinktorHttpClient;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Bridges archflow flow executions to Linktor conversations: whenever
 * a flow completes and its {@link ExecutionContext} carries a
 * {@code conversationId}, we post the flow's output text as a new
 * Linktor message in that conversation.
 *
 * <p>The flow is expected to write the reply either as its final
 * {@link FlowResult#getOutput()} (a plain {@code String}) or under the
 * context variable {@code "linktor.replyText"}. The listener is tiny
 * on purpose — more sophisticated behaviors (VRE templates, inline
 * tool calls, etc.) should live in dedicated flow steps that call
 * {@link LinktorHttpClient} directly.</p>
 *
 * <p>Errors are never propagated: a failed Linktor POST must not
 * break the flow's completion path. Failures are logged at
 * {@code WARN}.</p>
 */
public class LinktorFlowPublisher implements FlowLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(LinktorFlowPublisher.class);

    public static final String CTX_CONVERSATION_ID = "conversationId";
    public static final String CTX_REPLY_TEXT      = "linktor.replyText";
    public static final String CTX_AUTO_PUBLISH    = "linktor.autoPublish";

    private final LinktorHttpClient client;

    public LinktorFlowPublisher(LinktorHttpClient client) {
        this.client = client;
    }

    @Override
    public void onFlowCompleted(Flow flow, ExecutionContext context, FlowResult result, long durationMs) {
        if (context == null || result == null) return;

        Object autoFlag = context.get(CTX_AUTO_PUBLISH);
        if (autoFlag instanceof Boolean b && !b) {
            // Caller opted out — leave the conversation untouched.
            return;
        }

        Object convId = context.get(CTX_CONVERSATION_ID);
        if (!(convId instanceof String conversationId) || conversationId.isBlank()) {
            return;
        }

        String text = resolveReplyText(context, result);
        if (text == null || text.isBlank()) {
            log.debug("Flow {} completed without a linktor reply text; skipping publish", flow.getId());
            return;
        }

        try {
            client.sendMessage(conversationId, text);
            log.info("Published flow output to Linktor conversation={} chars={}",
                    conversationId, text.length());
        } catch (Exception e) {
            // Never block the flow completion on Linktor failures.
            log.warn("Failed to publish flow output to Linktor conversation={}: {}",
                    conversationId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveReplyText(ExecutionContext context, FlowResult result) {
        Object override = context.get(CTX_REPLY_TEXT);
        if (override instanceof String s && !s.isBlank()) return s;

        Optional<Object> output = result.getOutput();
        if (output.isPresent()) {
            Object v = output.get();
            if (v instanceof String s) return s;
            if (v instanceof java.util.Map<?, ?> m) {
                Object text = m.get("text");
                if (text instanceof String s) return s;
                Object reply = m.get("reply");
                if (reply instanceof String s) return s;
            }
        }
        return null;
    }
}
