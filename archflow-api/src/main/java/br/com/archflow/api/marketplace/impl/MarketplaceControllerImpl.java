package br.com.archflow.api.marketplace.impl;

import br.com.archflow.api.marketplace.MarketplaceController;
import br.com.archflow.api.marketplace.dto.ExtensionResponse;
import br.com.archflow.api.marketplace.dto.InstallExtensionRequest;
import br.com.archflow.marketplace.installer.ExtensionInstaller;
import br.com.archflow.marketplace.manifest.ExtensionManifest;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link MarketplaceController}.
 *
 * <p>Delegates to {@link ExtensionRegistry} for listing and searching,
 * and to {@link ExtensionInstaller} for install and uninstall operations.</p>
 */
public class MarketplaceControllerImpl implements MarketplaceController {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceControllerImpl.class);

    private final ExtensionRegistry registry;
    private final ExtensionInstaller installer;

    /**
     * Creates a new MarketplaceControllerImpl.
     *
     * @param registry The extension registry
     * @param installer The extension installer
     */
    public MarketplaceControllerImpl(ExtensionRegistry registry, ExtensionInstaller installer) {
        this.registry = registry;
        this.installer = installer;
    }

    @Override
    public List<ExtensionResponse> listExtensions() {
        log.debug("Listing all extensions");

        return registry.getAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ExtensionResponse getExtension(String extensionId) {
        log.debug("Getting extension: {}", extensionId);

        ExtensionManifest manifest = registry.getById(extensionId)
                .orElseThrow(() -> new ExtensionNotFoundException(extensionId));

        return toResponse(manifest);
    }

    @Override
    public ExtensionResponse installExtension(InstallExtensionRequest request) {
        log.info("Installing extension from: {}", request.manifestUrl());

        try {
            Path manifestPath = Path.of(request.manifestUrl());
            ExtensionManifest manifest = installer.loadManifest(manifestPath);

            ExtensionInstaller.InstallationResult result =
                    installer.install(manifest, manifestPath.getParent());

            if (!result.success()) {
                throw new RuntimeException("Installation failed: " + result.message());
            }

            return toResponse(result.manifest());
        } catch (IOException e) {
            log.error("Failed to install extension from {}", request.manifestUrl(), e);
            throw new RuntimeException("Failed to load manifest: " + e.getMessage(), e);
        }
    }

    @Override
    public void uninstallExtension(String extensionId) {
        log.info("Uninstalling extension: {}", extensionId);

        if (!registry.isRegistered(extensionId)) {
            throw new ExtensionNotFoundException(extensionId);
        }

        boolean uninstalled = installer.uninstall(extensionId);
        if (!uninstalled) {
            throw new RuntimeException("Failed to uninstall extension: " + extensionId);
        }
    }

    @Override
    public List<ExtensionResponse> searchExtensions(String query, String type) {
        log.debug("Searching extensions with query: '{}', type: '{}'", query, type);

        List<ExtensionManifest> results;

        if (query != null && !query.isBlank()) {
            results = registry.search(query);
        } else {
            results = registry.getAll();
        }

        // Filter by type if specified
        if (type != null && !type.isBlank()) {
            results = results.stream()
                    .filter(m -> type.equalsIgnoreCase(m.getType()))
                    .collect(Collectors.toList());
        }

        return results.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Converts an ExtensionManifest to an ExtensionResponse DTO.
     */
    private ExtensionResponse toResponse(ExtensionManifest manifest) {
        return new ExtensionResponse(
                manifest.getId(),
                manifest.getName(),
                manifest.getVersion(),
                manifest.getDisplayName(),
                manifest.getAuthor(),
                manifest.getDescription(),
                manifest.getType(),
                manifest.getPermissions(),
                registry.isRegistered(manifest.getId())
        );
    }
}
