package br.com.archflow.api.web.conversation;

import br.com.archflow.api.conversation.ConversationController;
import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class SpringConversationController {

    private final ConversationController delegate;

    public SpringConversationController(ConversationController delegate) {
        this.delegate = delegate;
    }

    @PostMapping("/suspend")
    public ConversationResponse suspend(@RequestBody SuspendRequest request) { return delegate.suspend(request); }

    @PostMapping("/resume")
    public ConversationResponse resume(@RequestBody ResumeRequest request) { return delegate.resume(request); }

    @GetMapping("/{id}")
    public ConversationResponse getConversation(@PathVariable String id) { return delegate.getConversation(id); }

    @GetMapping("/{id}/messages")
    public ConversationResponse getMessages(@PathVariable String id) { return delegate.getMessages(id); }

    @DeleteMapping("/{id}")
    public void cancel(@PathVariable String id) { delegate.cancel(id); }

    @ExceptionHandler(ConversationController.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ConversationController.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
