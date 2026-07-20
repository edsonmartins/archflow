package br.com.archflow.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

/**
 * Web MVC configuration: CORS for the Vite dev server, and the static-file /
 * SPA-fallback wiring that lets archflow-api serve the bundled frontend.
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

    /**
     * Prefixos que NUNCA devem cair no index.html: são API/infra, e um deep-link
     * inexistente sob eles deve ser um 404 honesto, não a SPA. Mantido alinhado
     * com os prefixos protegidos do {@link JwtAuthenticationFilter}.
     */
    private static final List<String> NON_SPA_PREFIXES = List.of(
            "api/", "archflow/", "mcp", "ag-ui", "actuator/", "realtime/", "ws/", "error");

    private final String[] allowedOrigins;
    private final String[] staticLocations;

    public WebMvcConfiguration(
            @Value("${archflow.cors.allowed-origins:http://localhost:5173,http://localhost:5174}") String[] allowedOrigins,
            @Value("${spring.web.resources.static-locations:classpath:/static/}") String[] staticLocations) {
        this.allowedOrigins = allowedOrigins;
        this.staticLocations = staticLocations;
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

    /**
     * Serve os estáticos do SPA e faz o fallback do BrowserRouter: um deep-link
     * como {@code /workflows} ou um F5 em {@code /editor/42} não corresponde a
     * arquivo nenhum, e sem este resolver o Spring devolveria 404 — o SPA nunca
     * carregaria fora da raiz. O resolver devolve o arquivo quando ele existe e,
     * caso contrário, cai para {@code index.html} para que o React Router assuma
     * no cliente. Prefixos de API ({@link #NON_SPA_PREFIXES}) são preservados
     * como 404 reais em vez de "engolidos" pela SPA.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(staticLocations)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Não é arquivo. Se for API/infra, deixa 404 (retorna null).
                        for (String prefix : NON_SPA_PREFIXES) {
                            if (resourcePath.equals(prefix) || resourcePath.startsWith(prefix)) {
                                return null;
                            }
                        }
                        // Rota do SPA (ou raiz): devolve index.html.
                        Resource index = location.createRelative("index.html");
                        return (index.exists() && index.isReadable()) ? index : null;
                    }
                });
    }
}
