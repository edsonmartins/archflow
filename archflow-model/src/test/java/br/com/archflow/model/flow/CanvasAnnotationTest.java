package br.com.archflow.model.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Anotações do canvas não são passos")
class CanvasAnnotationTest {

    @ParameterizedTest
    @ValueSource(strings = {"sticky-note", "group-frame", "section-divider", "STICKY-NOTE", " Group-Frame "})
    @DisplayName("os três tipos de anotação, em qualquer caixa")
    void anotacoes(String kind) {
        assertThat(CanvasAnnotation.isAnnotation(kind)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "llm-chat", "condition", "sticky", "note", ""})
    @DisplayName("nós de verdade não são anotação")
    void naoAnotacoes(String kind) {
        assertThat(CanvasAnnotation.isAnnotation(kind)).isFalse();
    }

    @Test
    @DisplayName("passo do documento: vale o componentId ou o type (o designer grava o type em maiúsculas)")
    void passoDoDocumento() {
        assertThat(CanvasAnnotation.isAnnotation(Map.of("id", "n1", "componentId", "sticky-note"))).isTrue();
        assertThat(CanvasAnnotation.isAnnotation(Map.of("id", "n2", "type", "SECTION-DIVIDER"))).isTrue();
        assertThat(CanvasAnnotation.isAnnotation(Map.of("id", "n3", "componentId", "decision", "type", "DECISION")))
                .isFalse();
        Map<String, Object> semTipo = new HashMap<>();
        semTipo.put("componentId", null);
        assertThat(CanvasAnnotation.isAnnotation(semTipo)).isFalse();
        assertThat(CanvasAnnotation.isAnnotation((Map<?, ?>) null)).isFalse();
        assertThat(CanvasAnnotation.isAnnotation((String) null)).isFalse();
    }
}
