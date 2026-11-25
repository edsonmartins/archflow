package br.com.archflow.api.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 403 para {@link TenantAccessDeniedException}, no mesmo envelope do {@link AdminRoleInterceptor}
 * ({@code error}, {@code message}, {@code status}). Sem este mapeamento a exceção sairia como 500, e
 * a UI não distinguiria "você não pode ver isto" de uma falha do servidor.
 */
@RestControllerAdvice
public class TenantAccessDeniedHandler {

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(TenantAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "FORBIDDEN", "message", e.getMessage(), "status", 403));
    }
}
