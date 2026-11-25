package br.com.archflow.plugin.api.spi;

import br.com.archflow.model.ai.domain.Goal;
import br.com.archflow.model.ai.domain.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ComponentInputs: a entrada no tipo que o plugin espera")
class ComponentInputsTest {

    @Test
    void textoPassaComoEsta() {
        assertThat(ComponentInputs.text("oi")).isEqualTo("oi");
        assertThat(ComponentInputs.text(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "message"})
    void textoDeUmMapaVemDoCampoDeTexto(String key) {
        assertThat(ComponentInputs.text(Map.of(key, "olá", "sessionId", "s-1"))).isEqualTo("olá");
    }

    @Test
    void inputVenceMessageEInputAninhadoEhLido() {
        Map<String, Object> ambos = new LinkedHashMap<>();
        ambos.put("message", "segundo");
        ambos.put("input", "primeiro");
        assertThat(ComponentInputs.text(ambos)).isEqualTo("primeiro");
        assertThat(ComponentInputs.text(Map.of("input", Map.of("message", "dentro")))).isEqualTo("dentro");
    }

    @Test
    void outrasChavesNaoSaoAdivinhadasComoTexto() {
        // Só input/message (a regra do mcp-agent); "text" ou "prompt" não viram a mensagem do usuário.
        assertThat(ComponentInputs.textField(Map.of("prompt", "olá"))).isNull();
        assertThat(ComponentInputs.text(Map.of("text", "olá"))).isEqualTo("{\"text\":\"olá\"}");
    }

    @Test
    void mapaSemCampoDeTextoViraJson() {
        Map<String, Object> mapa = new LinkedHashMap<>();
        mapa.put("a", 1);
        mapa.put("b", List.of(true, "x\"y"));
        mapa.put("c", null);
        assertThat(ComponentInputs.text(mapa)).isEqualTo("{\"a\":1,\"b\":[true,\"x\\\"y\"],\"c\":null}");
    }

    @Test
    void taskPassaComoEsta() {
        Task task = Task.of("research", Map.of());
        assertThat(ComponentInputs.task(task, "outro", "request")).isSameAs(task);
        assertThat(ComponentInputs.task(null, "outro", "request")).isNull();
    }

    @Test
    void stringViraTaskDoTipoPadraoComOTexto() {
        Task task = ComponentInputs.task("pesquise X", "analyze", "query");
        assertThat(task.type()).isEqualTo("analyze");
        // Só a chave que o agente lê; nada de espalhar o texto por request/description/query.
        assertThat(task.parameters()).containsExactlyEntriesOf(Map.of("query", "pesquise X"));
        assertThat(ComponentInputs.task("pesquise X", "research", null).parameters()).isEmpty();
    }

    @Test
    void mapaViraTaskComTipoEParametrosDeclarados() {
        Task task = ComponentInputs.task(
                Map.of("type", "compare", "parameters", Map.of("message", "a vs b", "extra", 1)), "analyze", "query");
        assertThat(task.type()).isEqualTo("compare");
        assertThat(task.parameters()).containsEntry("message", "a vs b").containsEntry("extra", 1)
                .containsEntry("query", "a vs b");
    }

    @Test
    void mapaDoExecutarViraTaskDoTipoPadrao() {
        Task task = ComponentInputs.task(Map.of("message", "oi", "sessionId", "s-1"), "conversation", "request");
        assertThat(task.type()).isEqualTo("conversation");
        assertThat(task.parameters()).containsEntry("request", "oi").containsEntry("sessionId", "s-1");
    }

    @Test
    void goalDeStringEDeMapa() {
        Goal goal = Goal.of("g", List.of());
        assertThat(ComponentInputs.goal(goal)).isSameAs(goal);
        assertThat(ComponentInputs.goal("aprender").description()).isEqualTo("aprender");
        Goal fromMap = ComponentInputs.goal(Map.of("description", "d", "successCriteria", List.of("c1")));
        assertThat(fromMap.description()).isEqualTo("d");
        assertThat(fromMap.successCriteria()).containsExactly("c1");
        assertThat(ComponentInputs.goal(Map.of("message", "m")).description()).isEqualTo("m");
    }
}
