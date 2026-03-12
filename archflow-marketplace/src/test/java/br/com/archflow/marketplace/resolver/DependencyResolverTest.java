package br.com.archflow.marketplace.resolver;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DependencyResolver}.
 */
class DependencyResolverTest {

    private DependencyResolver resolver;
    private ExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        ExtensionRegistry.reset();
        registry = ExtensionRegistry.getInstance();
        resolver = new DependencyResolver(registry);
    }

    @AfterEach
    void tearDown() {
        ExtensionRegistry.reset();
    }

    @Test
    void shouldResolveNoDependencies() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("standalone-ext")
                .version("1.0.0")
                .entryPoint("StandaloneExt")
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolve(manifest);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.missingDependencies()).isEmpty();
        assertThat(result.installOrder()).contains("standalone-ext");
    }

    @Test
    void shouldResolveSatisfiedDependencies() {
        // Register the dependency in the registry
        ExtensionManifest depManifest = ExtensionManifest.builder()
                .name("base-lib")
                .version("1.0.0")
                .entryPoint("BaseLib")
                .build();
        registry.register(depManifest);

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("my-ext")
                .version("1.0.0")
                .entryPoint("MyExt")
                .addDependency(new ExtensionManifest.Dependency("base-lib", "1.0.0"))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolve(manifest);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.satisfiedDependencies()).contains("base-lib");
    }

    @Test
    void shouldDetectMissingDependencies() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("my-ext")
                .version("1.0.0")
                .entryPoint("MyExt")
                .addDependency(new ExtensionManifest.Dependency("missing-lib", "1.0.0"))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolve(manifest);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.missingDependencies()).anyMatch(s -> s.contains("missing-lib"));
    }

    @Test
    void shouldDetectCircularDependencies() {
        // ext-a depends on ext-b, ext-b depends on ext-a
        ExtensionManifest extA = ExtensionManifest.builder()
                .name("ext-a")
                .version("1.0.0")
                .entryPoint("ExtA")
                .addDependency(new ExtensionManifest.Dependency("ext-b", "1.0.0"))
                .build();

        ExtensionManifest extB = ExtensionManifest.builder()
                .name("ext-b")
                .version("1.0.0")
                .entryPoint("ExtB")
                .addDependency(new ExtensionManifest.Dependency("ext-a", "1.0.0"))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolveAll(List.of(extA, extB));

        assertThat(result.satisfied()).isFalse();
        assertThat(result.hasError()).isTrue();
        assertThat(result.error()).containsIgnoringCase("circular");
    }

    @Test
    void shouldHandleOptionalDependencies() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("my-ext")
                .version("1.0.0")
                .entryPoint("MyExt")
                .addDependency(new ExtensionManifest.Dependency(
                        "optional-lib", "1.0.0", null, true))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolve(manifest);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.missingDependencies()).isEmpty();
    }

    @Test
    void shouldResolveTransitiveDependencies() {
        // Register ext-c in registry
        ExtensionManifest extC = ExtensionManifest.builder()
                .name("ext-c")
                .version("1.0.0")
                .entryPoint("ExtC")
                .build();
        registry.register(extC);

        // ext-b depends on ext-c (already installed)
        ExtensionManifest extB = ExtensionManifest.builder()
                .name("ext-b")
                .version("1.0.0")
                .entryPoint("ExtB")
                .addDependency(new ExtensionManifest.Dependency("ext-c", "1.0.0"))
                .build();

        // ext-a depends on ext-b (in batch)
        ExtensionManifest extA = ExtensionManifest.builder()
                .name("ext-a")
                .version("1.0.0")
                .entryPoint("ExtA")
                .addDependency(new ExtensionManifest.Dependency("ext-b", "1.0.0"))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolveAll(List.of(extA, extB));

        assertThat(result.satisfied()).isTrue();
        assertThat(result.satisfiedDependencies()).contains("ext-b", "ext-c");
    }

    @Test
    void shouldReturnInstallOrder() {
        // Register ext-c
        ExtensionManifest extC = ExtensionManifest.builder()
                .name("ext-c")
                .version("1.0.0")
                .entryPoint("ExtC")
                .build();
        registry.register(extC);

        // ext-b depends on ext-c
        ExtensionManifest extB = ExtensionManifest.builder()
                .name("ext-b")
                .version("1.0.0")
                .entryPoint("ExtB")
                .addDependency(new ExtensionManifest.Dependency("ext-c", "1.0.0"))
                .build();

        // ext-a depends on ext-b
        ExtensionManifest extA = ExtensionManifest.builder()
                .name("ext-a")
                .version("1.0.0")
                .entryPoint("ExtA")
                .addDependency(new ExtensionManifest.Dependency("ext-b", "1.0.0"))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolveAll(List.of(extA, extB));

        assertThat(result.satisfied()).isTrue();
        assertThat(result.installOrder()).isNotEmpty();

        // ext-c should come before ext-b, and ext-b before ext-a
        int idxC = result.installOrder().indexOf("ext-c");
        int idxB = result.installOrder().indexOf("ext-b");
        int idxA = result.installOrder().indexOf("ext-a");
        assertThat(idxC).isLessThan(idxB);
        assertThat(idxB).isLessThan(idxA);
    }

    @Test
    void shouldHandleEmptyDependencyList() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("no-deps")
                .version("1.0.0")
                .entryPoint("NoDeps")
                .dependencies(List.of())
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolve(manifest);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.missingDependencies()).isEmpty();
    }

    @Test
    void shouldDetectVersionMismatch() {
        // Register version 1.0.0 but the dependency requires 2.0.0+
        ExtensionManifest installed = ExtensionManifest.builder()
                .name("base-lib")
                .version("1.0.0")
                .entryPoint("BaseLib")
                .build();
        registry.register(installed);

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("my-ext")
                .version("1.0.0")
                .entryPoint("MyExt")
                .addDependency(new ExtensionManifest.Dependency(
                        "base-lib", "2.0.0", "2.0.0", false))
                .build();

        DependencyResolver.ResolutionResult result = resolver.resolve(manifest);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.missingDependencies())
                .anyMatch(s -> s.contains("base-lib") && s.contains("need") && s.contains("have"));
    }

    @Test
    void shouldResolveBatchDependencies() {
        // Resolve a batch of independent extensions
        ExtensionManifest ext1 = ExtensionManifest.builder()
                .name("ext-1")
                .version("1.0.0")
                .entryPoint("Ext1")
                .build();

        ExtensionManifest ext2 = ExtensionManifest.builder()
                .name("ext-2")
                .version("1.0.0")
                .entryPoint("Ext2")
                .build();

        ExtensionManifest ext3 = ExtensionManifest.builder()
                .name("ext-3")
                .version("1.0.0")
                .entryPoint("Ext3")
                .addDependency(new ExtensionManifest.Dependency("ext-1", "1.0.0"))
                .build();

        DependencyResolver.ResolutionResult result =
                resolver.resolveAll(List.of(ext1, ext2, ext3));

        assertThat(result.satisfied()).isTrue();
        assertThat(result.installOrder()).containsAll(List.of("ext-1", "ext-2", "ext-3"));

        // ext-1 should come before ext-3
        int idx1 = result.installOrder().indexOf("ext-1");
        int idx3 = result.installOrder().indexOf("ext-3");
        assertThat(idx1).isLessThan(idx3);
    }
}
