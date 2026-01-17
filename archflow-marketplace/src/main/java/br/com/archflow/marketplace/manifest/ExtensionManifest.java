package br.com.archflow.marketplace.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manifest for an archflow extension.
 *
 * <p>Every extension in the marketplace must include a manifest.json file
 * with metadata about the extension, its permissions, dependencies, and signature.</p>
 *
 * <p>Example manifest.json:
 * <pre>{@code
 * {
 *   "name": "slack-integration",
 *   "version": "1.0.0",
 *   "displayName": "Slack Integration",
 *   "description": "Send notifications to Slack channels",
 *   "author": "Acme Corp",
 *   "email": "support@acme.com",
 *   "license": "MIT",
 *   "archflowVersion": "1.0.0",
 *   "minArchflowVersion": "1.0.0",
 *   "entryPoint": "SlackExtension",
 *   "type": "tool",
 *   "permissions": ["network:https://api.slack.com"],
 *   "dependencies": [],
 *   "signature": "SHA256:abc123...",
 *   "icon": "icon.svg",
 *   "homepage": "https://acme.com/slack-integration",
 *   "repository": "https://github.com/acme/slack-integration",
 *   "keywords": ["slack", "notification", "messaging"],
 *   "categories": ["integration", "notification"]
 * }
 * }</pre>
 *
 * @see ExtensionPermission
 * @see ExtensionType
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExtensionManifest {

    private final String name;
    private final String version;
    private final String displayName;
    private final String description;
    private final String author;
    private final String email;
    private final String license;
    private final String archflowVersion;
    private final String minArchflowVersion;
    private final String entryPoint;
    private final String type;
    private final Set<String> permissions;
    private final List<Dependency> dependencies;
    private final String signature;
    private final String icon;
    private final String homepage;
    private final String repository;
    private final Set<String> keywords;
    private final Set<String> categories;
    private final Map<String, Object> metadata;

    private ExtensionManifest(Builder builder) {
        this.name = builder.name;
        this.version = builder.version;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.author = builder.author;
        this.email = builder.email;
        this.license = builder.license;
        this.archflowVersion = builder.archflowVersion;
        this.minArchflowVersion = builder.minArchflowVersion;
        this.entryPoint = builder.entryPoint;
        this.type = builder.type != null ? builder.type : ExtensionType.TOOL;
        this.permissions = builder.permissions != null ? Set.copyOf(builder.permissions) : Set.of();
        this.dependencies = builder.dependencies != null ? List.copyOf(builder.dependencies) : List.of();
        this.signature = builder.signature;
        this.icon = builder.icon;
        this.homepage = builder.homepage;
        this.repository = builder.repository;
        this.keywords = builder.keywords != null ? Set.copyOf(builder.keywords) : Set.of();
        this.categories = builder.categories != null ? Set.copyOf(builder.categories) : Set.of();
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : null;
    }

    /**
     * Creates a new builder for ExtensionManifest.
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public String getEmail() {
        return email;
    }

    public String getLicense() {
        return license;
    }

    public String getArchflowVersion() {
        return archflowVersion;
    }

    public String getMinArchflowVersion() {
        return minArchflowVersion;
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    public String getType() {
        return type;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public List<Dependency> getDependencies() {
        return dependencies;
    }

    public String getSignature() {
        return signature;
    }

    public String getIcon() {
        return icon;
    }

    public String getHomepage() {
        return homepage;
    }

    public String getRepository() {
        return repository;
    }

    public Set<String> getKeywords() {
        return keywords;
    }

    public Set<String> getCategories() {
        return categories;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Gets the unique identifier for this extension (name:version).
     */
    public String getId() {
        return name + ":" + version;
    }

    /**
     * Checks if this extension is compatible with the given archflow version.
     */
    public boolean isCompatibleWith(String archflowVersion) {
        if (minArchflowVersion == null) {
            return true;
        }
        return compareVersions(archflowVersion, minArchflowVersion) >= 0;
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            int n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    /**
     * Checks if this extension requires the given permission.
     */
    public boolean requiresPermission(String permission) {
        return permissions.contains(permission);
    }

    /**
     * Checks if this extension has any dangerous permissions.
     */
    public boolean hasDangerousPermissions() {
        return permissions.stream()
                .anyMatch(p -> p.startsWith("network:") ||
                            p.startsWith("file:") ||
                            p.startsWith("exec:"));
    }

    /**
     * Extension types.
     */
    public static class ExtensionType {
        public static final String TOOL = "tool";
        public static final String AGENT = "agent";
        public static final String CHAIN = "chain";
        public static final String FLOW = "flow";
        public static final String OBSERVER = "observer";
        public static final String MIDDLEWARE = "middleware";
    }

    /**
     * Standard permissions.
     */
    public static class ExtensionPermission {
        // Network permissions
        public static final String NETWORK_ANY = "network:*";
        public static final String NETWORK_HTTPS = "network:https:*";
        public static final String NETWORK_HTTP = "network:http:*";

        // File permissions
        public static final String FILE_READ = "file:read";
        public static final String FILE_WRITE = "file:write";
        public static final String FILE_ANY = "file:*";

        // Execution permissions
        public static final String EXEC = "exec:*";
        public static final String EXEC_SANDBOXED = "exec:sandboxed";

        // System permissions
        public static final String SYSTEM_ENV = "system:env";
        public static final String SYSTEM_PROPERTIES = "system:properties";

        // AI permissions
        public static final String AI_CHAT = "ai:chat";
        public static final String AI_STREAM = "ai:stream";
    }

    /**
     * A dependency required by this extension.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dependency(
            @JsonProperty("name") String name,
            @JsonProperty("version") String version,
            @JsonProperty("minVersion") String minVersion,
            @JsonProperty("optional") boolean optional
    ) {
        public Dependency(String name, String version) {
            this(name, version, null, false);
        }

        public Dependency(String name, String version, boolean optional) {
            this(name, version, null, optional);
        }

        public String getId() {
            return name + ":" + version;
        }

        public boolean isSatisfiedBy(String installedVersion) {
            if (minVersion == null) {
                return version.equals(installedVersion);
            }
            // Simple version comparison - in production use semver library
            return compareVersions(installedVersion, minVersion) >= 0;
        }

        private int compareVersions(String v1, String v2) {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
                int n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
                if (n1 != n2) {
                    return Integer.compare(n1, n2);
                }
            }
            return 0;
        }
    }

    /**
     * Builder for constructing ExtensionManifest instances.
     */
    public static class Builder {
        private String name;
        private String version;
        private String displayName;
        private String description;
        private String author;
        private String email;
        private String license;
        private String archflowVersion;
        private String minArchflowVersion;
        private String entryPoint;
        private String type;
        private Set<String> permissions;
        private List<Dependency> dependencies;
        private String signature;
        private String icon;
        private String homepage;
        private String repository;
        private Set<String> keywords;
        private Set<String> categories;
        private Map<String, Object> metadata;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder author(String author) {
            this.author = author;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder license(String license) {
            this.license = license;
            return this;
        }

        public Builder archflowVersion(String archflowVersion) {
            this.archflowVersion = archflowVersion;
            return this;
        }

        public Builder minArchflowVersion(String minArchflowVersion) {
            this.minArchflowVersion = minArchflowVersion;
            return this;
        }

        public Builder entryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder addPermission(String permission) {
            if (this.permissions == null) {
                this.permissions = new java.util.HashSet<>();
            }
            this.permissions.add(permission);
            return this;
        }

        public Builder dependencies(List<Dependency> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Builder addDependency(Dependency dependency) {
            if (this.dependencies == null) {
                this.dependencies = new java.util.ArrayList<>();
            }
            this.dependencies.add(dependency);
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder homepage(String homepage) {
            this.homepage = homepage;
            return this;
        }

        public Builder repository(String repository) {
            this.repository = repository;
            return this;
        }

        public Builder keywords(Set<String> keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder categories(Set<String> categories) {
            this.categories = categories;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ExtensionManifest build() {
            if (name == null || version == null) {
                throw new IllegalStateException("name and version are required");
            }
            if (entryPoint == null) {
                throw new IllegalStateException("entryPoint is required");
            }
            return new ExtensionManifest(this);
        }
    }
}
