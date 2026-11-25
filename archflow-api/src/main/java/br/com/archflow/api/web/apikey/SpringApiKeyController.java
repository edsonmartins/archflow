package br.com.archflow.api.web.apikey;

import br.com.archflow.api.apikey.ApiKeyController;
import br.com.archflow.api.apikey.dto.ApiKeyResponse;
import br.com.archflow.api.apikey.dto.ApiKeyScopeResponse;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.api.apikey.dto.CreateApiKeyResponse;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.security.apikey.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/apikeys}: as chaves de API do usuário autenticado.
 *
 * <p>O dono e o tenant da chave são os do principal que o {@link JwtAuthenticationFilter} validou
 * (subject e claim {@code tenantId} do token), como o decisor de uma aprovação (RCHFLW-38). Antes
 * vinham do cabeçalho {@code X-User-Id}: quem o enviasse escolhia de quem eram as chaves que via ou
 * revogava, e sem ele todas caíam sob {@code anonymous}, compartilhadas (RCHFLW-86). O cabeçalho
 * agora é ignorado.
 *
 * <p>Sem principal (autenticação desligada, ou requisição sem token num ambiente que a deixa passar)
 * não há dono a quem a chave pertencer, e a resposta é {@code 401} — nada de dono genérico.
 * Uma requisição autenticada por uma chave de API não chega aqui: nenhum escopo cobre a gestão de
 * chaves, e o filtro a recusa antes ({@code 403}).
 */
@RestController
@RequestMapping("/api/apikeys")
public class SpringApiKeyController {

    private final ApiKeyController delegate;

    public SpringApiKeyController(ApiKeyController delegate) {
        this.delegate = delegate;
    }

    /**
     * O vocabulário de escopos — a UI monta o seletor a partir daqui (RCHFLW-83) —, com o que o papel
     * de quem pergunta concede.
     */
    @GetMapping("/scopes")
    public List<ApiKeyScopeResponse> listScopes(HttpServletRequest http) {
        return delegate.listScopes(principal(http).userId());
    }

    @PostMapping
    public CreateApiKeyResponse createApiKey(HttpServletRequest http, @RequestBody CreateApiKeyRequest request) {
        Principal principal = principal(http);
        return delegate.createApiKey(principal.tenantId(), principal.userId(), request);
    }

    @GetMapping
    public List<ApiKeyResponse> listApiKeys(HttpServletRequest http) {
        Principal principal = principal(http);
        return delegate.listApiKeys(principal.tenantId(), principal.userId());
    }

    @GetMapping("/{keyId}")
    public ApiKeyResponse getApiKey(HttpServletRequest http, @PathVariable String keyId) {
        Principal principal = principal(http);
        return delegate.getApiKey(principal.tenantId(), principal.userId(), keyId);
    }

    /** 204: sem corpo de propósito — um 200 vazio quebrava o {@code response.json()} do cliente da UI. */
    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(HttpServletRequest http, @PathVariable String keyId) {
        Principal principal = principal(http);
        delegate.revokeApiKey(principal.tenantId(), principal.userId(), keyId);
    }

    @ExceptionHandler(ApiKeyController.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ApiKeyController.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    /** Escopo além do papel, ou usuário que não pode ter chave: {@code 403} com a frase do serviço. */
    @ExceptionHandler(ApiKeyService.ApiKeyForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ApiKeyService.ApiKeyForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "FORBIDDEN", "message", ex.getMessage()));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthenticated(UnauthenticatedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "UNAUTHENTICATED", "message", ex.getMessage()));
    }

    /** Quem está autenticado nesta requisição e em que tenant. */
    record Principal(String tenantId, String userId) {
    }

    /**
     * Subject do token e o tenant dele. Token sem a claim {@code tenantId} pertence ao tenant padrão
     * — a mesma regra do {@code ImpersonationFilter}. A impersonação não conta: a chave é do usuário,
     * no tenant dele, não no que ele está olhando.
     */
    static Principal principal(HttpServletRequest http) {
        Object userId = http.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
        if (!(userId instanceof String id) || id.isBlank()) {
            throw new UnauthenticatedException("API key management requires an authenticated user");
        }
        Object tenant = http.getAttribute(JwtAuthenticationFilter.ATTR_TENANT);
        String tenantId = tenant instanceof String t && !t.isBlank() ? t.trim() : TenantContext.DEFAULT_TENANT;
        return new Principal(tenantId, id);
    }

    /** Não há principal autenticado na requisição. */
    static class UnauthenticatedException extends RuntimeException {
        UnauthenticatedException(String message) {
            super(message);
        }
    }
}
