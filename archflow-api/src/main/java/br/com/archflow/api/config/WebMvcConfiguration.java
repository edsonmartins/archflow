package br.com.archflow.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration: CORS for the Vite dev server and allowed origins.
 *
 * <p>Headers permitidos são uma lista explícita (fase 5.7): com
 * {@code allowCredentials(true)}, um wildcard de headers amplia
 * desnecessariamente a superfície de requests cross-origin autenticados.
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    /** Headers que o frontend realmente envia (Authorization, tenant/impersonation, userId). */
    static final String[] ALLOWED_HEADERS = {
            "Authorization", "Content-Type", "Accept",
            "X-Tenant-Id", "X-Impersonate-Tenant", "X-User-Id"
    };

    private final String[] allowedOrigins;

    public WebMvcConfiguration(
            @Value("${archflow.cors.allowed-origins:http://localhost:5173,http://localhost:5174}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders(ALLOWED_HEADERS)
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
