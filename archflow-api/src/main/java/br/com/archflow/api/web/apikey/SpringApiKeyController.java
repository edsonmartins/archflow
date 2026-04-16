package br.com.archflow.api.web.apikey;

import br.com.archflow.api.apikey.ApiKeyController;
import br.com.archflow.api.apikey.dto.ApiKeyResponse;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.api.apikey.dto.CreateApiKeyResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/apikeys")
public class SpringApiKeyController {

    private final ApiKeyController delegate;

    public SpringApiKeyController(ApiKeyController delegate) {
        this.delegate = delegate;
    }

    @PostMapping
    public CreateApiKeyResponse createApiKey(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
            @RequestBody CreateApiKeyRequest request) {
        return delegate.createApiKey(userId, request);
    }

    @GetMapping
    public List<ApiKeyResponse> listApiKeys(@RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return delegate.listApiKeys(userId);
    }

    @GetMapping("/{id}")
    public ApiKeyResponse getApiKey(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
            @PathVariable String id) {
        return delegate.getApiKey(userId, id);
    }

    @DeleteMapping("/{id}")
    public void revokeApiKey(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
            @PathVariable String id) {
        delegate.revokeApiKey(userId, id);
    }

    @ExceptionHandler(ApiKeyController.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ApiKeyController.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
