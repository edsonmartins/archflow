package br.com.archflow.security.cors;

/**
 * Resolver for determining the current runtime environment.
 *
 * <p>This class checks various sources to determine the environment:
 * <ol>
 *   <li>System property "archflow.env"</li>
 *   <li>System property "ENV"</li>
 *   <li>Environment variable "ARCHFLOW_ENV"</li>
 *   <li>Environment variable "ENV"</li>
 *   <li>Spring "spring.profiles.active" property (if available)</li>
 * </ol>
 *
 * <p>Possible values: "development", "testing", "staging", "production"</p>
 */
public final class EnvironmentResolver {

    private static final String ENVIRONMENT_PROPERTY = "archflow.env";
    private static final String ENV_PROPERTY = "ENV";
    private static final String ENVIRONMENT_VAR = "ARCHFLOW_ENV";
    private static final String SPRING_PROFILES = "spring.profiles.active";

    private static String cachedEnvironment;

    private EnvironmentResolver() {
        // Utility class - prevent instantiation
    }

    /**
     * Gets the current environment.
     *
     * <p>Results are cached after first call.</p>
     *
     * @return The environment name (never null)
     */
    public static String getEnvironment() {
        if (cachedEnvironment != null) {
            return cachedEnvironment;
        }

        cachedEnvironment = resolveEnvironment();
        return cachedEnvironment;
    }

    /**
     * Checks if the current environment is production.
     */
    public static boolean isProduction() {
        return "production".equals(getEnvironment()) || "prod".equals(getEnvironment());
    }

    /**
     * Checks if the current environment is development.
     */
    public static boolean isDevelopment() {
        return "development".equals(getEnvironment()) || "dev".equals(getEnvironment());
    }

    /**
     * Checks if the current environment is testing.
     */
    public static boolean isTesting() {
        return "testing".equals(getEnvironment()) || "test".equals(getEnvironment());
    }

    /**
     * Checks if the current environment is staging.
     */
    public static boolean isStaging() {
        return "staging".equals(getEnvironment());
    }

    /**
     * Resets the cached environment (useful for testing).
     */
    public static void reset() {
        cachedEnvironment = null;
    }

    /**
     * Sets the environment programmatically (useful for testing).
     */
    public static void setEnvironment(String environment) {
        cachedEnvironment = normalizeEnvironment(environment);
    }

    /**
     * Gets the appropriate CORS configuration for the current environment.
     */
    public static CorsConfiguration getCorsConfiguration() {
        return CorsConfiguration.forEnvironment(getEnvironment());
    }

    // Private methods

    private static String resolveEnvironment() {
        // Check system properties first
        String env = System.getProperty(ENVIRONMENT_PROPERTY);
        if (env != null && !env.isEmpty()) {
            return normalizeEnvironment(env);
        }

        env = System.getProperty(ENV_PROPERTY);
        if (env != null && !env.isEmpty()) {
            return normalizeEnvironment(env);
        }

        // Check environment variables
        env = System.getenv(ENVIRONMENT_VAR);
        if (env != null && !env.isEmpty()) {
            return normalizeEnvironment(env);
        }

        env = System.getenv("ENV");
        if (env != null && !env.isEmpty()) {
            return normalizeEnvironment(env);
        }

        // Check Spring profiles (if Spring is available)
        try {
            env = System.getProperty(SPRING_PROFILES);
            if (env != null && !env.isEmpty()) {
                // Spring can have multiple profiles separated by comma
                String[] profiles = env.split(",");
                for (String profile : profiles) {
                    String normalized = normalizeEnvironment(profile.trim());
                    if (!normalized.equals("development")) {
                        return normalized; // Return first non-default profile
                    }
                }
            }
        } catch (Exception e) {
            // Spring not available - ignore
        }

        // Default to development
        return "development";
    }

    private static String normalizeEnvironment(String env) {
        if (env == null || env.isEmpty()) {
            return "development";
        }

        String normalized = env.toLowerCase().trim();

        return switch (normalized) {
            case "prod", "production" -> "production";
            case "dev", "development" -> "development";
            case "test", "testing" -> "testing";
            case "staging", "stage" -> "staging";
            default -> "development"; // Default for unknown environments
        };
    }

    /**
     * Information about the current environment.
     */
    public static class EnvironmentInfo {
        private final String name;
        private final boolean production;
        private final boolean development;
        private final boolean testing;
        private final boolean staging;

        private EnvironmentInfo(String name) {
            this.name = name;
            this.production = "production".equals(name);
            this.development = "development".equals(name);
            this.testing = "testing".equals(name);
            this.staging = "staging".equals(name);
        }

        public static EnvironmentInfo current() {
            return new EnvironmentInfo(getEnvironment());
        }

        public String getName() {
            return name;
        }

        public boolean isProduction() {
            return production;
        }

        public boolean isDevelopment() {
            return development;
        }

        public boolean isTesting() {
            return testing;
        }

        public boolean isStaging() {
            return staging;
        }

        @Override
        public String toString() {
            return "EnvironmentInfo{" +
                    "name='" + name + '\'' +
                    ", production=" + production +
                    ", development=" + development +
                    ", testing=" + testing +
                    ", staging=" + staging +
                    '}';
        }
    }
}
