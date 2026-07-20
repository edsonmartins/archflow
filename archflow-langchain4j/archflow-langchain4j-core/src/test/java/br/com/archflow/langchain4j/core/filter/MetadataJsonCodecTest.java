package br.com.archflow.langchain4j.core.filter;

import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataJsonCodecTest {

    @Test
    @DisplayName("round-trip preserves string and numeric types")
    void roundTripPreservesTypes() {
        var original = new Metadata(Map.of(
                "segment", "tenant-a",
                "year", 2024,
                "count", 9_999_999_999L,
                "score", 4.5
        ));

        var json = MetadataJsonCodec.toJson(original);
        var restored = MetadataJsonCodec.fromJson(json);

        assertThat(restored.toMap())
                .containsEntry("segment", "tenant-a")
                .containsEntry("year", 2024)
                .containsEntry("count", 9_999_999_999L)
                .containsEntry("score", 4.5);
    }

    @Test
    @DisplayName("UUID values are persisted as strings")
    void uuidAsString() {
        var uuid = UUID.randomUUID();
        var original = new Metadata(Map.of("id", uuid));

        var restored = MetadataJsonCodec.fromJson(MetadataJsonCodec.toJson(original));

        assertThat(restored.toMap()).containsEntry("id", uuid.toString());
    }

    @Test
    @DisplayName("null and empty metadata serialize to null")
    void nullAndEmptyToJson() {
        assertThat(MetadataJsonCodec.toJson(null)).isNull();
        assertThat(MetadataJsonCodec.toJson(new Metadata())).isNull();
    }

    @Test
    @DisplayName("null/blank JSON deserializes to empty metadata")
    void nullAndBlankFromJson() {
        assertThat(MetadataJsonCodec.fromJson(null).toMap()).isEmpty();
        assertThat(MetadataJsonCodec.fromJson("   ").toMap()).isEmpty();
    }

    @Test
    @DisplayName("invalid JSON throws IllegalArgumentException")
    void invalidJson() {
        assertThatThrownBy(() -> MetadataJsonCodec.fromJson("not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid metadata JSON");
    }

    @Test
    @DisplayName("toMetadata coerces unsupported types and skips nulls")
    void toMetadataCoercion() {
        var raw = new HashMap<String, Object>();
        raw.put("flag", Boolean.TRUE);
        raw.put("name", "x");
        raw.put("nothing", null);

        var metadata = MetadataJsonCodec.toMetadata(raw);

        assertThat(metadata.toMap())
                .containsEntry("flag", "true")
                .containsEntry("name", "x")
                .doesNotContainKey("nothing");
    }

    @Test
    @DisplayName("toMetadata of null map yields empty metadata")
    void toMetadataNull() {
        assertThat(MetadataJsonCodec.toMetadata(null).toMap()).isEmpty();
    }
}
