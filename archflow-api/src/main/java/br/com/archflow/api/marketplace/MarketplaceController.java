package br.com.archflow.api.marketplace;

import br.com.archflow.api.marketplace.dto.ExtensionResponse;
import br.com.archflow.api.marketplace.dto.InstallExtensionRequest;

import java.util.List;

/**
 * REST controller for marketplace extension management.
 *
 * <p>This interface defines the contract for marketplace endpoints.
 * Implementations can be provided for different web frameworks (Spring Boot, JAX-RS, etc.).</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/extensions - List all extensions</li>
 *   <li>GET /api/extensions/{id} - Get a specific extension</li>
 *   <li>POST /api/extensions/install - Install an extension</li>
 *   <li>DELETE /api/extensions/{id} - Uninstall an extension</li>
 *   <li>GET /api/extensions/search?q=query&amp;type=TYPE - Search extensions</li>
 * </ul>
 */
public interface MarketplaceController {

    /**
     * Lists all registered extensions.
     *
     * @return List of extension responses
     */
    List<ExtensionResponse> listExtensions();

    /**
     * Gets a specific extension by ID.
     *
     * @param extensionId The extension ID (name:version)
     * @return The extension response
     * @throws ExtensionNotFoundException if the extension doesn't exist
     */
    ExtensionResponse getExtension(String extensionId);

    /**
     * Installs an extension from a manifest URL.
     *
     * @param request The install request with manifest URL and options
     * @return The installed extension response
     */
    ExtensionResponse installExtension(InstallExtensionRequest request);

    /**
     * Uninstalls an extension.
     *
     * @param extensionId The extension ID (name:version)
     * @throws ExtensionNotFoundException if the extension doesn't exist
     */
    void uninstallExtension(String extensionId);

    /**
     * Searches extensions by query and optional type filter.
     *
     * @param query The search query
     * @param type Optional extension type filter (e.g., "tool", "agent", "chain")
     * @return List of matching extension responses
     */
    List<ExtensionResponse> searchExtensions(String query, String type);

    /**
     * Exception thrown when an extension is not found.
     */
    class ExtensionNotFoundException extends RuntimeException {
        public ExtensionNotFoundException(String extensionId) {
            super("Extension not found: " + extensionId);
        }
    }
}
