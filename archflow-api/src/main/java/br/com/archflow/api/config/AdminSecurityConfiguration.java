package br.com.archflow.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra o {@link AdminRoleInterceptor} nos paths de administração global.
 * Configuração separada de {@link WebMvcConfiguration} (CORS) para manter a
 * superfície de admin-enforcement isolada e testável.
 */
@Configuration
public class AdminSecurityConfiguration implements WebMvcConfigurer {

    /** Paths que exigem role ADMIN/SUPERADMIN. */
    static final String[] ADMIN_PATHS = {"/api/admin/global/**", "/api/admin/tenants/**"};

    private final boolean authEnabled;

    public AdminSecurityConfiguration(
            @Value("${archflow.security.auth.enabled:false}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminRoleInterceptor(authEnabled))
                .addPathPatterns(ADMIN_PATHS);
    }
}
