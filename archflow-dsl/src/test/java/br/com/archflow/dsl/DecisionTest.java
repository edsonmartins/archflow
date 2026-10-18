package br.com.archflow.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O construtor do nó de decisão.
 *
 * <p>O que se protege: o documento emitido tem as chaves que o {@code DecisionComponent}
 * lê, com os nomes que ele lê; a ordem das perguntas sobrevive (a primeira é a
 * {@code primary} por padrão); e a chave de API nunca entra no documento — só a
 * referência.
 */
@DisplayName("Decision — a config do nó por método")
class DecisionTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> passo(NodeSpec spec) {
        Map<String, Object> documento = Workflows.define("f").step("p", spec).build().toMap();
        return ((List<Map<String, Object>>) documento.get("steps")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Decision decision) {
        return (Map<String, Object>) passo(decision.spec()).get("config");
    }

    private static Decision minima() {
        return Decision.node().choice("intencao", "O cliente fecha o pedido?",
                Map.of("PEDIDO", "Confirma itens.", "DUVIDA", "Pergunta sobre produto."));
    }

    @Test
    @DisplayName("o passo sai como decision/decision, com a operação do nó")
    void forma() {
        Map<String, Object> p = passo(minima().spec());
        assertThat(p).containsEntry("type", "decision").containsEntry("componentId", "decision")
                .containsEntry("operation", "decide");
    }

    @Test
    @DisplayName("as chaves que o nó lê, com os nomes que ele lê")
    void chaves() {
        Map<String, Object> config = config(Decision.node()
                .provider("http-decisions")
                .model("typesafe/jev-1.14")
                .choice("intencao", "Qual a intenção?", Map.of("PEDIDO", "fecha", "DUVIDA", "pergunta"))
                .score("urgencia", "Quão urgente?", "pode esperar", "esta semana", "agora")
                .yesNo("e_defeito", "É um defeito?")
                .state(Map.of("mensagem", "${input}", "plano", "${cliente.plano}"))
                .thresholds(0.9, 0.5)
                .primary("intencao")
                .gate("intencao", "e_defeito")
                .escalateOnError()
                .timeout(Duration.ofSeconds(8))
                .fallback("llm", "openai/gpt-4o-mini")
                .cascade("http-decisions", "upstage/solar-decide")
                .acceptAt(0.92)
                .consensus("liquid/d1")
                .consensusEscalatesOnDisagree());

        assertThat(config).containsEntry("provider", "http-decisions")
                .containsEntry("model", "typesafe/jev-1.14")
                .containsEntry("primary", "intencao")
                .containsEntry("gate", List.of("intencao", "e_defeito"))
                .containsEntry("onError", "ESCALATE")
                .containsEntry("timeoutMs", 8000L)
                .containsEntry("acceptAt", 0.92)
                .containsEntry("state", Map.of("mensagem", "${input}", "plano", "${cliente.plano}"))
                .containsEntry("thresholds", Map.of("auto", 0.9, "review", 0.5))
                .containsEntry("fallbacks", List.of(Map.of("provider", "llm", "model", "openai/gpt-4o-mini")))
                .containsEntry("cascade", List.of(Map.of("provider", "http-decisions",
                        "model", "upstage/solar-decide")))
                .containsEntry("consensus", Map.of("models", List.of(Map.of("model", "liquid/d1")),
                        "onDisagree", "ESCALATE"));

        @SuppressWarnings("unchecked")
        Map<String, Object> perguntas = (Map<String, Object>) config.get("questions");
        assertThat(perguntas.keySet()).as("a ordem declarada: a primeira é a primary por padrão")
                .containsExactly("intencao", "urgencia", "e_defeito");
        assertThat(perguntas.get("intencao")).isEqualTo(Map.of("type", "choice",
                "instructions", "Qual a intenção?",
                "criteria", Map.of("PEDIDO", "fecha", "DUVIDA", "pergunta")));
        assertThat(perguntas.get("urgencia")).isEqualTo(Map.of("type", "score",
                "instructions", "Quão urgente?",
                "criteria", List.of("pode esperar", "esta semana", "agora")));
        assertThat(perguntas.get("e_defeito")).isEqualTo(Map.of("type", "noul",
                "instructions", "É um defeito?"));
    }

    /** A chave nunca vai no documento: só a referência que o hospedeiro resolve. */
    @Test
    @DisplayName("keyRef e endpoint vão em providerOptions do provedor principal — declarado antes ou depois")
    void opcoesDoProvedorPrincipal() {
        Map<String, Object> esperado = Map.of("http-decisions",
                Map.of("keyRef", "openrouter", "endpoint", "https://openrouter.ai/api/v1/systemone"));

        assertThat(config(minima().keyRef("openrouter")
                .endpoint("https://openrouter.ai/api/v1/systemone")))
                .containsEntry("providerOptions", esperado);
        assertThat(config(minima().keyRef("openrouter").provider("http-decisions")
                .endpoint("https://openrouter.ai/api/v1/systemone")))
                .containsEntry("providerOptions", esperado);

        assertThat(config(minima().provider("langchain4j-decisions").keyRef("typesafe")
                .providerOptions("llm", Map.of("maxConfidence", 0.95))))
                .containsEntry("providerOptions", Map.of(
                        "langchain4j-decisions", Map.of("keyRef", "typesafe"),
                        "llm", Map.of("maxConfidence", 0.95)));

        assertThat(config(minima())).doesNotContainKeys("providerOptions", "apiKey");
    }

    @Test
    @DisplayName("sem pergunta não há nó: o erro aparece em código, não no runtime")
    void semPerguntas() {
        assertThatThrownBy(() -> Decision.node().model("m").spec())
                .isInstanceOf(DslValidationException.class)
                .hasMessageContaining("sem perguntas");
    }

    @Test
    @DisplayName("o que o nó recusaria, a DSL recusa antes")
    void validacoes() {
        assertThatThrownBy(() -> minima().choice("intencao", "de novo", Map.of("A", "a")))
                .hasMessageContaining("duplicada");
        assertThatThrownBy(() -> Decision.node().choice("x", "?", Map.of()))
                .hasMessageContaining("sem opções");
        assertThatThrownBy(() -> Decision.node().score("x", "?", "só um"))
                .hasMessageContaining("2 níveis");
        assertThatThrownBy(() -> minima().thresholds(0.5, 0.9))
                .hasMessageContaining("acima de auto");
        assertThatThrownBy(() -> minima().thresholds(1.5, 0.5))
                .hasMessageContaining("fora de [0, 1]");
        assertThatThrownBy(() -> minima().gate())
                .hasMessageContaining("gate vazio");
        assertThatThrownBy(() -> minima().timeout(Duration.ZERO))
                .hasMessageContaining("positivo");
    }

    @Test
    @DisplayName("route() emite o ramo na aresta — e nenhuma condição à mão")
    @SuppressWarnings("unchecked")
    void arestasPorRota() {
        Map<String, Object> documento = Workflows.define("triagem")
                .step("intencao", minima().model("typesafe/jev-1.14"))
                .step("fecha", Nodes.component("fechar"))
                .step("humano", Nodes.approval())
                .route("intencao", "fecha", Decision.AUTO)
                .route("intencao", "humano", Decision.REVIEW)
                .route("intencao", "humano", Decision.ESCALATE)
                .build().toMap();

        Map<String, Object> no = ((List<Map<String, Object>>) documento.get("steps")).get(0);
        List<Map<String, Object>> arestas = (List<Map<String, Object>>) no.get("connections");
        assertThat(arestas).extracting(a -> a.get("branch"))
                .containsExactly("AUTO", "REVIEW", "ESCALATE");
        assertThat(arestas).extracting(a -> a.get("targetId"))
                .containsExactly("fecha", "humano", "humano");
        assertThat(arestas).allSatisfy(a -> assertThat(a).doesNotContainKey("condition")
                .containsEntry("isErrorPath", false));
    }

    @Test
    @DisplayName("ramo vazio é recusado")
    void ramoVazio() {
        assertThatThrownBy(() -> Workflows.define("f")
                .step("a", Nodes.component("x")).step("b", Nodes.component("y"))
                .route("a", "b", " "))
                .isInstanceOf(DslValidationException.class)
                .hasMessageContaining("ramo vazio");
    }

    /** JSON → código → JSON: o ramo volta como ramo, não como condição expandida. */
    @Test
    @DisplayName("o gerador de código devolve .route(...) para uma aresta com branch")
    void geradorDeCodigo() {
        Map<String, Object> documento = Workflows.define("triagem")
                .step("intencao", minima())
                .step("fecha", Nodes.component("fechar"))
                .route("intencao", "fecha", Decision.AUTO)
                .build().toMap();

        GeneratedCode code = WorkflowCodeGenerator.from(documento).generate();
        assertThat(code.source()).contains(".route(\"intencao\", \"fecha\", \"AUTO\")");
        assertThat(code.unrepresented()).isEmpty();
    }
}
