package br.com.archflow.api.web.linktor;

import br.com.archflow.model.escalation.EscalationChannel;
import br.com.archflow.model.escalation.EscalationException;
import br.com.archflow.model.escalation.EscalationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP entry point that lets any caller (UI, flow step, external
 * system) trigger a human-handoff through the currently-registered
 * {@link EscalationChannel}.
 */
@RestController
@RequestMapping("/api/admin/linktor/escalate")
public class SpringLinktorEscalationController {

    private final EscalationChannel channel;

    public SpringLinktorEscalationController(EscalationChannel channel) {
        this.channel = channel;
    }

    @PostMapping
    public ResponseEntity<?> escalate(@RequestBody EscalationRequest request) {
        try {
            channel.escalate(request);
            return ResponseEntity.ok(Map.of(
                    "status", "assigned",
                    "channel", channel.id(),
                    "conversationId", request.conversationId()));
        } catch (EscalationException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
