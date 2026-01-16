package br.com.archflow.security.cors;

import java.util.List;
import java.util.Set;

/**
 * Configuration for Cross-Origin Resource Sharing (CORS).
 *
 * <p>This class defines CORS policies that can be applied to different environments.
 * It follows the principle of least privilege - production environments should
 * have the most restrictive CORS settings.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * // Get configuration for current environment
 * CorsConfiguration config = CorsConfiguration.forEnvironment("production");
 *
 * // Apply to HTTP response
 * response.setHeader("Access-Control-Allow-Origin", config.allowedOrigins());
 * </pre>
 */
public class CorsConfiguration {

    private final String environment;
    private final Set<String> allowedOrigins;
    private final Set<String> allowedMethods;
    private final Set<String> allowedHeaders;
    private final Set<String> exposedHeaders;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;

    private CorsConfiguration(Builder builder) {
        this.environment = builder.environment;
        this.allowedOrigins = Set.copyOf(builder.allowedOrigins);
        this.allowedMethods = Set.copyOf(builder.allowedMethods);
        this.allowedHeaders = Set.copyOf(builder.allowedHeaders);
        this.exposedHeaders = Set.copyOf(builder.exposedHeaders);
        this.allowCredentials = builder.allowCredentials;
        this.maxAgeSeconds = builder.maxAgeSeconds;
    }

    /**
     * Gets the CORS configuration for a specific environment.
     *
     * @param environment The environment name (dev, test, staging, production)
     * @return A pre-configured CorsConfiguration for the environment
     */
    public static CorsConfiguration forEnvironment(String environment) {
        return switch (environment.toLowerCase()) {
            case "dev", "development" -> development();
            case "test", "testing" -> testing();
            case "staging" -> staging();
            case "prod", "production" -> production();
            default -> development(); // Default to least restrictive
        };
    }

    /**
     * Development configuration - most permissive.
     * Allows all origins for local development.
     */
    public static CorsConfiguration development() {
        return builder()
                .environment("development")
                .allowAllOrigins()
                .allowAllMethods()
                .allowAllHeaders()
                .exposeHeaders("Content-Type", "Authorization", "X-Total-Count")
                .allowCredentials(true)
                .maxAge(3600)
                .build();
    }

    /**
     * Testing configuration - allows test origins.
     */
    public static CorsConfiguration testing() {
        return builder()
                .environment("testing")
                .allowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:4200",
                        "http://localhost:5173",
                        "http://127.0.0.1:3000",
                        "http://127.0.0.1:4200",
                        "http://127.0.0.1:5173"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowAllHeaders()
                .exposeHeaders("Content-Type", "Authorization", "X-Total-Count")
                .allowCredentials(true)
                .maxAge(3600)
                .build();
    }

    /**
     * Staging configuration - allows staging domain and test subdomains.
     */
    public static CorsConfiguration staging() {
        return builder()
                .environment("staging")
                .allowedOrigins(
                        "https://staging.archflow.com",
                        "https://*.staging.archflow.com",
                        "https://archflow-staging.web.app"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders(
                        "Content-Type",
                        "Authorization",
                        "X-Request-ID",
                        "X-Client-Version"
                )
                .exposeHeaders("Content-Type", "Authorization", "X-Total-Count", "X-Request-ID")
                .allowCredentials(true)
                .maxAge(3600)
                .build();
    }

    /**
     * Production configuration - most restrictive.
     * Only allows production domains.
     */
    public static CorsConfiguration production() {
        return builder()
                .environment("production")
                .allowedOrigins(
                        "https://app.archflow.com",
                        "https://*.app.archflow.com",
                        "https://archflow.com",
                        "https://www.archflow.com"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH")
                .allowedHeaders(
                        "Content-Type",
                        "Authorization",
                        "X-Request-ID",
                        "X-Client-Version"
                )
                .exposeHeaders("X-Request-ID", "X-Total-Count")
                .allowCredentials(true)
                .maxAge(3600)
                .build();
    }

    /**
     * Creates a custom builder for CORS configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    // Getters

    public String getEnvironment() {
        return environment;
    }

    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public Set<String> getAllowedMethods() {
        return allowedMethods;
    }

    public Set<String> getAllowedHeaders() {
        return allowedHeaders;
    }

    public Set<String> getExposedHeaders() {
        return exposedHeaders;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * Checks if an origin is allowed based on this configuration.
     * Supports wildcard patterns like https://*.example.com
     */
    public boolean isOriginAllowed(String origin) {
        if (origin == null) {
            return false;
        }

        for (String allowedOrigin : allowedOrigins) {
            if (allowedOrigin.equals("*")) {
                return true;
            }
            if (allowedOrigin.equals(origin)) {
                return true;
            }
            // Handle wildcard subdomains
            if (allowedOrigin.contains("*")) {
                String pattern = allowedOrigin
                        .replace(".", "\\.")
                        .replace("*", ".*");
                if (origin.matches(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a method is allowed.
     */
    public boolean isMethodAllowed(String method) {
        return allowedOrigins.contains("*") || allowedMethods.contains(method.toUpperCase());
    }

    /**
     * Builder for creating custom CorsConfiguration instances.
     */
    public static class Builder {
        private String environment = "custom";
        private final java.util.Set<String> allowedOrigins = new java.util.HashSet<>();
        private final java.util.Set<String> allowedMethods = new java.util.HashSet<>();
        private final java.util.Set<String> allowedHeaders = new java.util.HashSet<>();
        private final java.util.Set<String> exposedHeaders = new java.util.HashSet<>();
        private boolean allowCredentials = false;
        private long maxAgeSeconds = 3600;

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder allowedOrigins(String... origins) {
            this.allowedOrigins.addAll(List.of(origins));
            return this;
        }

        public Builder allowAllOrigins() {
            this.allowedOrigins.add("*");
            return this;
        }

        public Builder allowedMethods(String... methods) {
            this.allowedMethods.addAll(List.of(methods));
            return this;
        }

        public Builder allowAllMethods() {
            this.allowedMethods.addAll(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
            return this;
        }

        public Builder allowedHeaders(String... headers) {
            this.allowedHeaders.addAll(List.of(headers));
            return this;
        }

        public Builder allowAllHeaders() {
            this.allowedHeaders.add("*");
            return this;
        }

        public Builder exposeHeaders(String... headers) {
            this.exposedHeaders.addAll(List.of(headers));
            return this;
        }

        public Builder allowCredentials(boolean allow) {
            this.allowCredentials = allow;
            return this;
        }

        public Builder maxAge(long seconds) {
            this.maxAgeSeconds = seconds;
            return this;
        }

        public CorsConfiguration build() {
            if (allowedOrigins.isEmpty()) {
                allowedOrigins.add("*");
            }
            if (allowedMethods.isEmpty()) {
                allowedMethods.addAll(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            }
            if (allowedHeaders.isEmpty()) {
                allowedHeaders.add("*");
            }
            return new CorsConfiguration(this);
        }
    }

    /**
     * Filter for applying CORS configuration to HTTP responses.
     * Can be used as a servlet filter or integrated with frameworks.
     */
    public static class CorsFilter {
        private final CorsConfiguration configuration;

        public CorsFilter(CorsConfiguration configuration) {
            this.configuration = configuration;
        }

        /**
         * Applies CORS headers to an HTTP response.
         * This is a generic method - framework-specific implementations
         * should override for better integration.
         */
        public void applyCorsHeaders(String origin, String method, CorsResponse response) {
            // Check origin
            if (configuration.isOriginAllowed(origin)) {
                String allowedOrigin = configuration.getAllowedOrigins().contains("*")
                        ? "*"
                        : origin;
                response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
            }

            // Apply other headers
            response.setHeader("Access-Control-Allow-Methods", String.join(", ", configuration.getAllowedMethods()));

            if (configuration.getAllowedHeaders().contains("*")) {
                response.setHeader("Access-Control-Allow-Headers", "*");
            } else {
                response.setHeader("Access-Control-Allow-Headers", String.join(", ", configuration.getAllowedHeaders()));
            }

            if (!configuration.getExposedHeaders().isEmpty()) {
                response.setHeader("Access-Control-Expose-Headers", String.join(", ", configuration.getExposedHeaders()));
            }

            if (configuration.isAllowCredentials()) {
                response.setHeader("Access-Control-Allow-Credentials", "true");
            }

            response.setHeader("Access-Control-Max-Age", String.valueOf(configuration.getMaxAgeSeconds()));
        }

        /**
         * Checks if a request should be handled as a preflight request.
         */
        public boolean isPreflightRequest(String method) {
            return "OPTIONS".equalsIgnoreCase(method);
        }

        /**
         * Gets the CORS configuration.
         */
        public CorsConfiguration getConfiguration() {
            return configuration;
        }
    }

    /**
     * Interface for setting HTTP response headers.
     * Implementations should wrap framework-specific response objects.
     */
    public interface CorsResponse {
        void setHeader(String name, String value);
    }
}
