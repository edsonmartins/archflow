package br.com.archflow.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 5.7: com {@code allowCredentials(true)}, os headers permitidos são uma
 * lista explícita — nunca {@code *}.
 */
@DisplayName("WebMvcConfiguration — CORS")
class WebMvcConfigurationCorsTest {

    /** Expõe o mapa protegido de configurações do registry. */
    private static class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }

    @Test
    @DisplayName("headers explícitos (sem wildcard) e credenciais habilitadas")
    void explicitHeadersNoWildcard() {
        var registry = new InspectableCorsRegistry();
        new WebMvcConfiguration(new String[] {"http://localhost:5173"}, new String[] {"classpath:/static/"})
                .addCorsMappings(registry);

        CorsConfiguration config = registry.configs().get("/api/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedHeaders())
                .doesNotContain("*")
                .containsExactlyInAnyOrder("Authorization", "Content-Type", "Accept",
                        "X-Tenant-Id", "X-Impersonate-Tenant", "X-User-Id");
        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(config.getAllowCredentials()).isTrue();
        assertThat(config.getExposedHeaders()).containsExactly("Authorization");
    }
}
