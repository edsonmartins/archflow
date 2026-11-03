package br.com.archflow.model.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProviderPreferences")
class ProviderPreferencesTest {

    @Test
    @DisplayName("propriedades em branco = nenhuma preferência, nada vai no corpo")
    void vazio() {
        assertThat(ProviderPreferences.of("", " ").isEmpty()).isTrue();
        assertThat(ProviderPreferences.of(null, null)).isEqualTo(ProviderPreferences.NONE);
        assertThat(ProviderPreferences.NONE.toWire()).isEmpty();
    }

    @Test
    @DisplayName("formato no fio: zdr e data_collection, nessa ordem, só os declarados")
    void wire() {
        assertThat(List.copyOf(ProviderPreferences.of("true", "DENY").toWire().keySet()))
                .containsExactly("zdr", "data_collection");
        assertThat(ProviderPreferences.of("true", "deny").toWire())
                .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
        assertThat(ProviderPreferences.of(null, "deny").toWire()).isEqualTo(Map.of("data_collection", "deny"));
    }

    @Test
    @DisplayName("valor mal escrito falha — ignorado, pareceria garantia dada sem estar")
    void invalidoFalha() {
        assertThatThrownBy(() -> ProviderPreferences.of("true", "dney"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("data_collection");
        assertThatThrownBy(() -> ProviderPreferences.of("sim", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zdr");
    }
}
