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
 * <p>O Linktor atribui a um usuário ({@code user_id} obrigatório) e não tem "devolver à fila": não
 * existe rota de desatribuição, e um {@code user_id} vazio é recusado com 400. Sem
 * {@code targetUserId} o canal falha antes de chamar o Linktor, dizendo por quê — antes mandava um id
 * vazio, prometendo um roteamento que o Linktor não faz.</p>
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
        String userId = request.targetUserId();
        if (userId == null || userId.isBlank()) {
            throw new EscalationException("Linktor escalate needs targetUserId for conversation="
                    + request.conversationId() + ": Linktor assigns to a user (user_id) and has no "
                    + "return-to-queue");
        }
        try {
            client.assignConversation(request.conversationId(), userId);
            log.info("Escalated conversation {} to user='{}' reason='{}'",
                    request.conversationId(), userId, request.reason());
        } catch (IOException | IllegalStateException e) {
            throw new EscalationException(
                    "Linktor escalate failed for conversation=" + request.conversationId()
                            + ": " + e.getMessage(), e);
        }
    }
}
