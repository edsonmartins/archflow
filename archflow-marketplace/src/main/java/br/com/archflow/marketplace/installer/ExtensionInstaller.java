package br.com.archflow.marketplace.installer;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
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
    private static final ObjectMapper objectMapper = createObjectMapper();

    private final ExtensionRegistry registry;
    private final Path extensionsDir;
    private final boolean verifySignatures;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    public ExtensionInstaller(Path extensionsDir) {
        this(extensionsDir, true);
    }

    public ExtensionInstaller(Path extensionsDir, boolean verifySignatures) {
        this.registry = ExtensionRegistry.getInstance();
        this.extensionsDir = extensionsDir;
        this.verifySignatures = verifySignatures;
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

        // Verify signature if enabled
        if (verifySignatures && manifest.getSignature() != null) {
            if (!verifySignature(manifest, extensionDir)) {
                return new InstallationResult(false, "Signature verification failed", null);
            }
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
        return true;
    }

    /**
     * Loads a manifest from a file.
     *
     * @param manifestPath Path to the manifest.json file
     * @return Parsed ExtensionManifest
     * @throws IOException If the file cannot be read or parsed
     */
    public ExtensionManifest loadManifest(Path manifestPath) throws IOException {
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
     * Verifies the signature of an extension.
     */
    public boolean verifySignature(ExtensionManifest manifest, Path extensionDir) {
        String signature = manifest.getSignature();
        if (signature == null || signature.isEmpty()) {
            log.warn("Extension {} has no signature", manifest.getId());
            return false;
        }

        // In a real implementation, this would verify against a known key
        // For now, accept any signature that starts with expected format
        if (signature.startsWith("SHA256:") || signature.startsWith("RSA:")) {
            log.debug("Signature format verified for {}", manifest.getId());
            return true;
        }

        log.warn("Invalid signature format for {}", manifest.getId());
        return false;
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
     * Gets the current archflow version.
     */
    public String getCurrentArchflowVersion() {
        // In a real implementation, this would read from a version file or package
        return "1.0.0";
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
