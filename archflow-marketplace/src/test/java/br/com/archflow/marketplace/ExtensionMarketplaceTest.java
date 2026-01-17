package br.com.archflow.marketplace;

import br.com.archflow.marketplace.installer.ExtensionInstaller;
import br.com.archflow.marketplace.manifest.ExtensionManifest;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for extension marketplace functionality.
 */
class ExtensionMarketplaceTest {

    private ExtensionRegistry registry;
    private ExtensionInstaller installer;

    @BeforeEach
    void setUp() {
        ExtensionRegistry.reset();
        registry = ExtensionRegistry.getInstance();
        installer = new ExtensionInstaller(Path.of("/tmp/extensions"), false);
    }

    @AfterEach
    void tearDown() {
        ExtensionRegistry.reset();
    }

    @Test
    void testRegisterExtension() {
        ExtensionManifest manifest = createTestManifest();

        boolean registered = registry.register(manifest);

        assertThat(registered).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.isRegistered("test-extension:1.0.0")).isTrue();
    }

    @Test
    void testGetExtensionById() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        Optional<ExtensionManifest> found = registry.getById("test-extension:1.0.0");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("test-extension");
        assertThat(found.get().getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void testGetExtensionByName() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        List<ExtensionManifest> found = registry.getByName("test-extension");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void testUnregisterExtension() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        ExtensionManifest unregistered = registry.unregister("test-extension:1.0.0");

        assertThat(unregistered).isNotNull();
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.isRegistered("test-extension:1.0.0")).isFalse();
    }

    @Test
    void testGetByCategory() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        List<ExtensionManifest> integrations = registry.getByCategory("integration");

        assertThat(integrations).hasSize(1);
        assertThat(integrations.get(0).getName()).isEqualTo("test-extension");
    }

    @Test
    void testSearchByKeyword() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        List<ExtensionManifest> results = registry.searchByKeyword("slack");

        assertThat(results).hasSize(1);
    }

    @Test
    void testSearch() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        List<ExtensionManifest> results = registry.search("notification");

        assertThat(results).hasSize(1);
    }

    @Test
    void testGetByPermission() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        List<ExtensionManifest> results = registry.getByPermission("network:https:*");

        assertThat(results).hasSize(1);
    }

    @Test
    void testGetDangerousExtensions() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        List<ExtensionManifest> dangerous = registry.getDangerousExtensions();

        assertThat(dangerous).hasSize(1);
    }

    @Test
    void testExtensionStats() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        ExtensionRegistry.ExtensionStats stats = registry.getStats();

        assertThat(stats.totalExtensions()).isEqualTo(1);
        assertThat(stats.uniqueNames()).isEqualTo(1);
        assertThat(stats.categories()).isGreaterThan(0);
        assertThat(stats.toolCount()).isEqualTo(1);
    }

    @Test
    void testEventListeners() {
        StringBuilder eventLog = new StringBuilder();
        registry.addListener(event -> {
            eventLog.append(event.type()).append(":").append(event.extensionId()).append(";");
        });

        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        assertThat(eventLog.toString()).contains("REGISTERED:test-extension:1.0.0");
    }

    @Test
    void testDependencyResolution() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-extension")
                .version("1.0.0")
                .entryPoint("TestExtension")
                .addDependency(new ExtensionManifest.Dependency("other-extension", "1.0.0"))
                .build();

        // Register the dependency first
        ExtensionManifest dependency = ExtensionManifest.builder()
                .name("other-extension")
                .version("1.0.0")
                .entryPoint("OtherExtension")
                .type(ExtensionManifest.ExtensionType.TOOL)
                .build();
        registry.register(dependency);

        ExtensionInstaller.DependencyResolutionResult result = installer.resolveDependencies(manifest);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.satisfiedDependencies()).contains("other-extension");
    }

    @Test
    void testUnresolvedDependency() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-extension")
                .version("1.0.0")
                .entryPoint("TestExtension")
                .addDependency(new ExtensionManifest.Dependency("missing-extension", "1.0.0"))
                .build();

        ExtensionInstaller.DependencyResolutionResult result = installer.resolveDependencies(manifest);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.missingDependencies()).contains("missing-extension (not installed)");
    }

    @Test
    void testVersionCompatibility() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-extension")
                .version("1.0.0")
                .entryPoint("TestExtension")
                .minArchflowVersion("0.9.0")
                .build();

        assertThat(manifest.isCompatibleWith("1.0.0")).isTrue();
        assertThat(manifest.isCompatibleWith("0.9.0")).isTrue();
        assertThat(manifest.isCompatibleWith("1.1.0")).isTrue();
        assertThat(manifest.isCompatibleWith("0.8.0")).isFalse();
    }

    @Test
    void testInstallExtension() {
        ExtensionManifest manifest = createTestManifest();

        ExtensionInstaller.InstallationResult result = installer.install(manifest, Path.of("/tmp"));

        assertThat(result.success()).isTrue();
        assertThat(registry.isRegistered("test-extension:1.0.0")).isTrue();
    }

    @Test
    void testInstallDuplicateExtension() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        ExtensionInstaller.InstallationResult result = installer.install(manifest, Path.of("/tmp"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("already installed");
    }

    @Test
    void testUninstallExtension() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        boolean uninstalled = installer.uninstall("test-extension:1.0.0");

        assertThat(uninstalled).isTrue();
        assertThat(registry.isRegistered("test-extension:1.0.0")).isFalse();
    }

    @Test
    void testUninstallWithDependents() {
        ExtensionManifest manifest1 = createTestManifest();
        ExtensionManifest manifest2 = ExtensionManifest.builder()
                .name("dependent-extension")
                .version("1.0.0")
                .displayName("Dependent Extension")
                .description("Depends on test-extension")
                .author("Test Author")
                .entryPoint("DependentExtension")
                .type(ExtensionManifest.ExtensionType.TOOL)
                .addDependency(new ExtensionManifest.Dependency("test-extension", "1.0.0"))
                .build();

        registry.register(manifest1);
        registry.register(manifest2);

        boolean uninstalled = installer.uninstall("test-extension:1.0.0");

        assertThat(uninstalled).isFalse();
        assertThat(registry.isRegistered("test-extension:1.0.0")).isTrue();
    }

    @Test
    void testGetByEntryPoint() {
        ExtensionManifest manifest = createTestManifest();
        registry.register(manifest);

        Optional<ExtensionManifest> found = registry.getByEntryPoint("com.example.TestExtension");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("test-extension");
    }

    @Test
    void testGetLatestByName() {
        ExtensionManifest v1 = ExtensionManifest.builder()
                .name("test-extension")
                .version("1.0.0")
                .entryPoint("TestExtension")
                .build();
        ExtensionManifest v2 = ExtensionManifest.builder()
                .name("test-extension")
                .version("2.0.0")
                .entryPoint("TestExtension")
                .build();

        registry.register(v1);
        registry.register(v2);

        Optional<ExtensionManifest> latest = registry.getLatestByName("test-extension");

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo("2.0.0");
    }

    private ExtensionManifest createTestManifest() {
        return ExtensionManifest.builder()
                .name("test-extension")
                .version("1.0.0")
                .displayName("Test Extension")
                .description("A test extension for unit tests")
                .author("Test Author")
                .email("test@example.com")
                .license("MIT")
                .archflowVersion("1.0.0")
                .entryPoint("com.example.TestExtension")
                .type(ExtensionManifest.ExtensionType.TOOL)
                .addPermission("network:https:*")
                .categories(Set.of("integration", "notification"))
                .keywords(Set.of("slack", "notification", "messaging"))
                .build();
    }
}
