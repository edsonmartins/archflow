package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DefaultComponentVersionManager")
class DefaultComponentVersionManagerTest {

    private DefaultComponentVersionManager manager;

    @BeforeEach
    void setUp() {
        manager = new DefaultComponentVersionManager();
    }

    private ComponentMetadata createMetadata(String id, String version) {
        return new ComponentMetadata(
                id, "Component " + id, "Description", ComponentType.TOOL, version,
                Set.of(), List.of(), Map.of(), Set.of()
        );
    }

    @Test
    @DisplayName("should register and retrieve version")
    void shouldRegisterAndRetrieve() {
        var meta = createMetadata("comp-1", "1.0.0");

        manager.registerVersion("comp-1", "1.0.0", meta);

        assertThat(manager.getVersion("comp-1", "1.0.0")).isPresent();
        assertThat(manager.getVersion("comp-1", "1.0.0").get().version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("should return empty for missing version")
    void shouldReturnEmptyForMissing() {
        assertThat(manager.getVersion("comp-1", "1.0.0")).isEmpty();
    }

    @Test
    @DisplayName("should list all versions sorted")
    void shouldListVersionsSorted() {
        manager.registerVersion("comp-1", "1.0.0", createMetadata("comp-1", "1.0.0"));
        manager.registerVersion("comp-1", "2.0.0", createMetadata("comp-1", "2.0.0"));
        manager.registerVersion("comp-1", "1.5.0", createMetadata("comp-1", "1.5.0"));

        var versions = manager.getVersions("comp-1");

        assertThat(versions).containsExactly("1.0.0", "1.5.0", "2.0.0");
    }

    @Test
    @DisplayName("should return empty list for unknown component")
    void shouldReturnEmptyListForUnknown() {
        assertThat(manager.getVersions("unknown")).isEmpty();
    }

    @Test
    @DisplayName("should get latest version")
    void shouldGetLatestVersion() {
        manager.registerVersion("comp-1", "1.0.0", createMetadata("comp-1", "1.0.0"));
        manager.registerVersion("comp-1", "2.0.0", createMetadata("comp-1", "2.0.0"));
        manager.registerVersion("comp-1", "1.5.0", createMetadata("comp-1", "1.5.0"));

        var latest = manager.getLatestVersion("comp-1");

        assertThat(latest).isPresent();
        assertThat(latest.get().version()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("should return empty latest for unknown component")
    void shouldReturnEmptyLatestForUnknown() {
        assertThat(manager.getLatestVersion("unknown")).isEmpty();
    }

    @Test
    @DisplayName("should handle multiple components independently")
    void shouldHandleMultipleComponents() {
        manager.registerVersion("comp-1", "1.0.0", createMetadata("comp-1", "1.0.0"));
        manager.registerVersion("comp-2", "2.0.0", createMetadata("comp-2", "2.0.0"));

        assertThat(manager.getVersions("comp-1")).containsExactly("1.0.0");
        assertThat(manager.getVersions("comp-2")).containsExactly("2.0.0");
    }

    @Test
    @DisplayName("should overwrite existing version")
    void shouldOverwriteExisting() {
        var v1 = createMetadata("comp-1", "1.0.0");
        var v1Updated = new ComponentMetadata(
                "comp-1", "Updated", "New desc", ComponentType.AGENT, "1.0.0",
                Set.of(), List.of(), Map.of(), Set.of()
        );

        manager.registerVersion("comp-1", "1.0.0", v1);
        manager.registerVersion("comp-1", "1.0.0", v1Updated);

        var result = manager.getVersion("comp-1", "1.0.0");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Updated");
    }
}
