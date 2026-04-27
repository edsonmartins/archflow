package br.com.archflow.api.linktor.escalation;

import br.com.archflow.api.linktor.LinktorHttpClient;
import br.com.archflow.model.escalation.EscalationChannel;
import br.com.archflow.model.escalation.EscalationException;
import br.com.archflow.model.escalation.EscalationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * {@link EscalationChannel} that hands a conversation off to a human
 * operator by calling Linktor's
 * {@code POST /conversations/{id}/assign} endpoint.
 *
 * <p>Assignment semantics:
 * <ul>
 *   <li>If {@code targetUserId} is provided, Linktor assigns directly
 *       to that user.</li>
 *   <li>If it is {@code null} we pass an empty userId — Linktor
 *       interprets that as "return to queue" which triggers the
 *       routing rules configured in the platform.</li>
 * </ul>
 */
public class LinktorEscalationChannel implements EscalationChannel {

    private static final Logger log = LoggerFactory.getLogger(LinktorEscalationChannel.class);

    private final LinktorHttpClient client;

    public LinktorEscalationChannel(LinktorHttpClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return "linktor";
    }

    @Override
    public void escalate(EscalationRequest request) {
        String userId = request.targetUserId() != null ? request.targetUserId() : "";
        try {
            client.assignConversation(request.conversationId(), userId);
            log.info("Escalated conversation {} to user='{}' reason='{}'",
                    request.conversationId(), userId.isBlank() ? "<queue>" : userId,
                    request.reason());
        } catch (IOException | IllegalStateException e) {
            throw new EscalationException(
                    "Linktor escalate failed for conversation=" + request.conversationId()
                            + ": " + e.getMessage(), e);
        }
    }
}
