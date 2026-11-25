package br.com.archflow.api.config;

/**
 * O cliente pediu um tenant (ou todos) que a identidade dele não alcança — respondido com 403 por
 * {@link TenantAccessDeniedHandler}. Ver {@link TenantScope}.
 */
public class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
