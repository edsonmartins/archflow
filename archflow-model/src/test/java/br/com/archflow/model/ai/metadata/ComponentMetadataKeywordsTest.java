package br.com.archflow.model.ai.metadata;

import br.com.archflow.model.ai.type.ComponentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ComponentMetadata keywords")
class ComponentMetadataKeywordsTest {

    @Test
    @DisplayName("compat constructor (no keywords) defaults to empty set")
    void compatConstructorDefaultsEmpty() {
        var md = new ComponentMetadata(
                "id", "Name", "desc", ComponentType.AGENT, "1.0.0",
                Set.of("cap"), List.of(), Map.of(), Set.of("tag"));
        assertThat(md.keywords()).isEmpty();
    }

    @Test
    @DisplayName("canonical constructor carries keywords")
    void canonicalCarriesKeywords() {
        var md = new ComponentMetadata(
                "id", "Name", "desc", ComponentType.AGENT, "1.0.0",
                Set.of("cap"), List.of(), Map.of(), Set.of("tag"), Set.of("invoice", "payment"));
        assertThat(md.keywords()).containsExactlyInAnyOrder("invoice", "payment");
    }

    @Test
    @DisplayName("null keywords are normalized to empty")
    void nullKeywordsNormalized() {
        var md = new ComponentMetadata(
                "id", "Name", "desc", ComponentType.AGENT, "1.0.0",
                Set.of("cap"), List.of(), Map.of(), Set.of("tag"), null);
        assertThat(md.keywords()).isNotNull().isEmpty();
    }
}
