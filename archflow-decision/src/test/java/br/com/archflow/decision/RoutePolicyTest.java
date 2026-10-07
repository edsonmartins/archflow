package br.com.archflow.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("rota, estado e catálogo de provedores")
class RoutePolicyTest {

    @Test
    @DisplayName("limiares padrão: 0,9 age, 0,5 pede revisão, abaixo escala; fronteiras incluídas")
    void padrao() {
        var p = RoutePolicy.DEFAULT;
        assertThat(p.route(0.95)).isEqualTo(RoutePolicy.Route.AUTO);
        assertThat(p.route(0.9)).isEqualTo(RoutePolicy.Route.AUTO);
        assertThat(p.route(0.89)).isEqualTo(RoutePolicy.Route.REVIEW);
        assertThat(p.route(0.5)).isEqualTo(RoutePolicy.Route.REVIEW);
        assertThat(p.route(0.49)).isEqualTo(RoutePolicy.Route.ESCALATE);
        assertThat(p.route(Double.NaN)).as("não se sabe: escala").isEqualTo(RoutePolicy.Route.ESCALATE);
    }

    @Test
    @DisplayName("limiares incoerentes são recusados")
    void invalidos() {
        assertThatThrownBy(() -> new RoutePolicy(0.4, 0.8)).hasMessageContaining("limiares");
        assertThatThrownBy(() -> new RoutePolicy(1.2, 0.5)).hasMessageContaining("limiares");
    }

    @Test
    @DisplayName("modelo de estado: valor único mantém o tipo; misturado vira texto; ausente vira vazio")
    void estado() {
        Map<String, Object> ctx = Map.of("obj", Map.of("a", 1), "nome", "Ana");
        java.util.function.Function<String, Object> lookup = ctx::get;

        assertThat(StateTemplate.resolve("${obj}", lookup)).isEqualTo(Map.of("a", 1));
        assertThat(StateTemplate.resolve("oi ${nome}!", lookup)).isEqualTo("oi Ana!");
        assertThat(StateTemplate.resolve("x${nada}y", lookup)).isEqualTo("xy");
        assertThat(StateTemplate.resolve(java.util.List.of("${nome}", 3), lookup))
                .isEqualTo(java.util.List.of("Ana", 3));
    }

    @Test
    @DisplayName("o catálogo traz os embutidos e aceita outros")
    void catalogo() {
        var providers = DecisionProviders.withDefaults();
        assertThat(providers.find("http-decisions")).isPresent();
        assertThat(providers.find("rules")).isPresent();
        assertThat(providers.find("nada")).isEmpty();
        assertThat(DecisionProviders.empty().register(new RulesDecisionProvider()).find("rules")).isPresent();
    }

    @Test
    @DisplayName("perguntas inválidas são recusadas na construção")
    void perguntas() {
        assertThatThrownBy(() -> new Question.Choice("q", "", Map.of())).hasMessageContaining("opção");
        assertThatThrownBy(() -> new Question.YesNo(" ", "", null, null)).hasMessageContaining("id");
        assertThatThrownBy(() -> new DecisionRequest("x", java.util.List.of())).hasMessageContaining("pergunta");
    }
}
