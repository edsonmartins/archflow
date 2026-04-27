package br.com.archflow.api.web.linktor;

import br.com.archflow.api.linktor.LinktorHttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Read/write proxy over Linktor's REST API for the admin inbox UI.
 *
 * <p>Endpoints stay under {@code /api/admin/linktor/inbox/*} so they
 * live alongside the config controller and inherit the same admin
 * role check. Responses mirror Linktor shapes 1:1 — we don't try to
 * re-model them, which keeps us in sync as Linktor evolves.</p>
 */
@RestController
@RequestMapping("/api/admin/linktor/inbox")
public class SpringLinktorInboxController {

    private final LinktorHttpClient client;

    public SpringLinktorInboxController(LinktorHttpClient client) {
        this.client = client;
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> conversations(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return safe(() -> client.listConversations(limit, offset));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> conversation(@PathVariable String id) {
        return safe(() -> client.getConversation(id));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<?> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit) {
        return safe(() -> client.listMessages(id, limit));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> sendMessage(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Object content = body.get("content");
        if (!(content instanceof String text) || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        return safe(() -> client.sendMessage(id, text));
    }

    @PostMapping("/conversations/{id}/assign")
    public ResponseEntity<?> assign(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Object userId = body.get("userId");
        if (!(userId instanceof String u) || u.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        return safe(() -> client.assignConversation(id, u));
    }

    @PostMapping("/conversations/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String id) {
        return safe(() -> client.resolveConversation(id));
    }

    @GetMapping("/contacts")
    public ResponseEntity<?> contacts(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return safe(() -> client.listContacts(limit, offset));
    }

    @GetMapping("/channels")
    public ResponseEntity<?> channels() {
        return safe(client::listChannels);
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Single place for error translation: network/HTTP failures become
     * {@code 502 Bad Gateway} so the UI can distinguish them from a
     * 4xx on our own endpoint.
     */
    private <T> ResponseEntity<?> safe(IoCallable<T> body) {
        try {
            return ResponseEntity.ok(body.call());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface IoCallable<T> {
        T call() throws IOException;
    }

    // Suppress unused warning for the List<?> import on some compilers
    @SuppressWarnings("unused")
    private static final List<?> KEEP_IMPORT = List.of();
}
