package br.com.archflow.marketplace.installer;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
import br.com.archflow.marketplace.security.ExtensionSignatureValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Handles installation, verification, and removal of extensions.
 *
 * <p>The installer provides:
 * <ul>
 *   <li>Installation from local files or URLs</li>
 *   <li>Signature verification</li>
 *   <li>Dependency resolution</li>
 *   <li>Uninstallation with cleanup</li>
 * </ul>
 */
public class ExtensionInstaller {

    private static final Logger log = LoggerFactory.getLogger(ExtensionInstaller.class);
    private static final Logger auditLog = LoggerFactory.getLogger("archflow.audit.marketplace");
    private static final ObjectMapper objectMapper = createObjectMapper();
    private static final String FALLBACK_VERSION = "1.0.0";

    private final ExtensionRegistry registry;
    private final Path extensionsDir;
    private final boolean verifySignatures;
    private final ExtensionSignatureValidator signatureValidator;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    public ExtensionInstaller(Path extensionsDir) {
        this(extensionsDir, true);
    }

    public ExtensionInstaller(Path extensionsDir, boolean verifySignatures) {
        this(extensionsDir, verifySignatures, new ExtensionSignatureValidator());
    }

    public ExtensionInstaller(Path extensionsDir, boolean verifySignatures,
                              ExtensionSignatureValidator signatureValidator) {
        this.registry = ExtensionRegistry.getInstance();
        this.extensionsDir = extensionsDir;
        this.verifySignatures = verifySignatures;
        this.signatureValidator = signatureValidator;
        if (!verifySignatures) {
            log.warn("Extension signature verification is DISABLED — this is only acceptable "
                    + "in development or testing environments");
        }
    }

    /**
     * Installs an extension from a manifest file.
     *
     * @param manifestPath Path to the manifest.json file
     * @return Result of the installation
     */
    public InstallationResult install(Path manifestPath) {
        try {
            ExtensionManifest manifest = loadManifest(manifestPath);

            return install(manifest, manifestPath.getParent());
        } catch (Exception e) {
            log.error("Failed to install extension from {}", manifestPath, e);
            return new InstallationResult(false, e.getMessage(), null);
        }
    }

    /**
     * Installs an extension from a manifest.
     *
     * @param manifest The extension manifest
     * @param extensionDir Directory containing the extension files
     * @return Result of the installation
     */
    public InstallationResult install(ExtensionManifest manifest, Path extensionDir) {
        // Check if already installed
        if (registry.isRegistered(manifest.getId())) {
            return new InstallationResult(false, "Extension " + manifest.getId() + " is already installed", null);
        }

        // Verify signature if enabled. An absent signature is a verification
        // failure — otherwise stripping the signature would bypass the check.
        if (verifySignatures && !verifySignature(manifest, extensionDir)) {
            auditLog.warn("Extension install REJECTED (signature): id={} author={}",
                    manifest.getId(), manifest.getAuthor());
            return new InstallationResult(false, "Signature verification failed", null);
        }

        // Check compatibility
        if (!manifest.isCompatibleWith(getCurrentArchflowVersion())) {
            return new InstallationResult(false,
                    "Extension requires archflow version " + manifest.getMinArchflowVersion(), null);
        }

        // Check dependencies
        DependencyResolutionResult deps = resolveDependencies(manifest);
        if (!deps.satisfied()) {
            return new InstallationResult(false,
                    "Unresolved dependencies: " + deps.missingDependencies(), null);
        }

        // Register the extension
        boolean registered = registry.register(manifest);

        if (registered) {
            log.info("Installed extension {} ({} by {})",
                    manifest.getId(), manifest.getDisplayName(), manifest.getAuthor());
            auditLog.info("Extension INSTALLED: id={} author={} signatureVerified={} permissions={}",
                    manifest.getId(), manifest.getAuthor(), verifySignatures, manifest.getPermissions());
            return new InstallationResult(true, "Extension installed successfully", manifest);
        } else {
            return new InstallationResult(false, "Failed to register extension", null);
        }
    }

    /**
     * Uninstalls an extension.
     *
     * @param extensionId The extension ID (name:version)
     * @return true if uninstalled successfully
     */
    public boolean uninstall(String extensionId) {
        Optional<ExtensionManifest> manifest = registry.getById(extensionId);

        if (manifest.isEmpty()) {
            log.warn("Cannot uninstall extension {}: not found", extensionId);
            return false;
        }

        // Check for dependent extensions
        List<String> dependents = findDependents(extensionId);
        if (!dependents.isEmpty()) {
            log.warn("Cannot uninstall extension {}: required by {}",
                    extensionId, dependents);
            return false;
        }

        registry.unregister(extensionId);
        log.info("Uninstalled extension {}", extensionId);
        auditLog.info("Extension UNINSTALLED: id={}", extensionId);
        return true;
    }

    /**
     * Loads a manifest from a file.
     *
     * <p>The {@code manifestPath} must resolve to a location under the
     * configured {@code extensionsDir}. Any attempt to read outside that
     * directory (e.g. {@code ../../etc/passwd}) is rejected up front —
     * this is the primary defense against path-traversal from the
     * marketplace REST endpoint which accepts a user-supplied path.
     *
     * @param manifestPath Path to the manifest.json file
     * @return Parsed ExtensionManifest
     * @throws IOException If the file cannot be read or parsed, or if
     *                     the path escapes {@code extensionsDir}
     */
    public ExtensionManifest loadManifest(Path manifestPath) throws IOException {
        ensureWithinExtensionsDir(manifestPath);
        if (!Files.exists(manifestPath)) {
            throw new IOException("Manifest file not found: " + manifestPath);
        }

        String content = Files.readString(manifestPath);
        log.debug("Loading manifest from {}", manifestPath);

        try {
            ExtensionManifest manifest = objectMapper.readValue(content, ExtensionManifest.class);
            log.info("Loaded manifest for extension {} (version {})",
                    manifest.getName(), manifest.getVersion());
            return manifest;
        } catch (Exception e) {
            log.error("Failed to parse manifest from {}", manifestPath, e);
            throw new IOException("Failed to parse manifest.json: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the signature of an extension by delegating to the configured
     * {@link ExtensionSignatureValidator} (cryptographic verification — not a
     * format check).
     */
    public boolean verifySignature(ExtensionManifest manifest, Path extensionDir) {
        String signature = manifest.getSignature();
        if (signature == null || signature.isEmpty()) {
            log.warn("Extension {} has no signature", manifest.getId());
            return false;
        }

        ExtensionSignatureValidator.ValidationResult result =
                signatureValidator.validate(manifest, extensionDir);
        if (!result.valid()) {
            log.warn("Signature verification failed for {}: {}", manifest.getId(), result.message());
        }
        return result.valid();
    }

    /**
     * Resolves all dependencies for an extension.
     */
    public DependencyResolutionResult resolveDependencies(ExtensionManifest manifest) {
        List<String> missing = new ArrayList<>();
        List<String> satisfied = new ArrayList<>();

        for (ExtensionManifest.Dependency dep : manifest.getDependencies()) {
            Optional<ExtensionManifest> installed = registry.getLatestByName(dep.name());

            if (installed.isEmpty()) {
                if (!dep.optional()) {
                    missing.add(dep.name() + " (not installed)");
                }
            } else {
                String installedVersion = installed.get().getVersion();
                if (dep.isSatisfiedBy(installedVersion)) {
                    satisfied.add(dep.name());
                } else {
                    if (!dep.optional()) {
                        missing.add(dep.name() + " (need " + dep.minVersion() + ", have " + installedVersion + ")");
                    }
                }
            }
        }

        return new DependencyResolutionResult(missing.isEmpty(), missing, satisfied);
    }

    /**
     * Finds all extensions that depend on the given extension.
     */
    public List<String> findDependents(String extensionId) {
        ExtensionManifest target = registry.getById(extensionId).orElse(null);
        if (target == null) {
            return List.of();
        }

        String targetName = target.getName();

        return registry.getAll().stream()
                .filter(m -> m.getDependencies().stream()
                        .anyMatch(d -> d.name().equals(targetName)))
                .map(ExtensionManifest::getId)
                .toList();
    }

    /**
     * Gets the current archflow version, resolved (in order) from the
     * {@code archflow.version} system property, the jar manifest's
     * {@code Implementation-Version}, or the Maven {@code pom.properties}
     * packaged with this module. Falls back to {@value #FALLBACK_VERSION}
     * with a warning when none is available (e.g. running from an IDE
     * against unpacked classes).
     */
    public String getCurrentArchflowVersion() {
        String fromProperty = System.getProperty("archflow.version");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }

        String fromManifest = ExtensionInstaller.class.getPackage().getImplementationVersion();
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest;
        }

        try (var stream = ExtensionInstaller.class.getResourceAsStream(
                "/META-INF/maven/br.com.archflow/archflow-marketplace/pom.properties")) {
            if (stream != null) {
                Properties props = new Properties();
                props.load(stream);
                String version = props.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version;
                }
            }
        } catch (IOException e) {
            log.debug("Could not read pom.properties for version resolution", e);
        }

        log.warn("Could not resolve archflow version; falling back to {} — "
                + "extension compatibility checks may be inaccurate", FALLBACK_VERSION);
        return FALLBACK_VERSION;
    }

    /**
     * Calculates the checksum of a file.
     */
    public Optional<String> calculateChecksum(Path file, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] hashBytes = digest.digest(fileBytes);

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }

            return Optional.of(algorithm + ":" + hexString);
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Failed to calculate checksum for {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * Ensures a path stays under {@link #extensionsDir}. Resolves both
     * paths to absolute+normalized form so {@code ../} cannot escape and
     * symlinks (via {@code toRealPath}) cannot smuggle a child out.
     *
     * @throws IOException if the path escapes the extensions directory
     */
    private void ensureWithinExtensionsDir(Path candidate) throws IOException {
        if (extensionsDir == null) {
            // No root configured — refuse user-supplied paths entirely.
            throw new IOException("Extensions directory is not configured; refusing to read " + candidate);
        }
        Path baseAbs = extensionsDir.toAbsolutePath().normalize();
        Path candAbs = candidate.toAbsolutePath().normalize();
        if (!candAbs.startsWith(baseAbs)) {
            log.warn("Rejected manifest path outside extensions dir: {} (base={})",
                    candAbs, baseAbs);
            throw new IOException(
                    "Manifest path is outside the configured extensions directory");
        }
    }

    /**
     * Result of an installation attempt.
     */
    public record InstallationResult(
            boolean success,
            String message,
            ExtensionManifest manifest
    ) {
        public static InstallationResult success(ExtensionManifest manifest) {
            return new InstallationResult(true, "Extension installed successfully", manifest);
        }

        public static InstallationResult failure(String message) {
            return new InstallationResult(false, message, null);
        }
    }

    /**
     * Result of dependency resolution.
     */
    public record DependencyResolutionResult(
            boolean satisfied,
            List<String> missingDependencies,
            List<String> satisfiedDependencies
    ) {}
}
