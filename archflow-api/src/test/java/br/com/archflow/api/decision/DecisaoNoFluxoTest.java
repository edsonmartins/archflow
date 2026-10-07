package br.com.archflow.api.decision;

import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O nó {@code decision} de ponta a ponta no que importa para o fluxo: a factory o cria, o passo o
 * executa e as <b>condições das arestas</b> — a única ramificação que o motor tem — leem o que ele
 * devolveu. O provedor é o de regras: determinístico, sem rede.
 */
@DisplayName("nó de decisão no fluxo")
class DecisaoNoFluxoTest {

    private final DefaultFlowStepFactory factory = new DefaultFlowStepFactory(
            mock(ComponentCatalog.class), null, null, null, null);

    private static Map<String, Object> no() {
        return Map.of("id", "triagem", "type", "decision", "componentId", "decision",
                "config", Map.of(
                        "provider", "rules",
                        "primary", "time",
                        "questions", Map.of(
                                "time", Map.of("type", "choice", "instructions", "Quem cuida?",
                                        "criteria", Map.of("pagamento", "cobrança", "conta", "login")),
                                "urgente", Map.of("type", "noul")),
                        "providerOptions", Map.of("rules", Map.of("rules", Map.of(
                                "time", Map.of("match", Map.of("pagamento", List.of("pix", "boleto"),
                                        "conta", List.of("senha")), "otherwise", "conta"),
                                "urgente", Map.of("matchAny", List.of("urgente", "fora do ar")))))));
    }

    private ExecutionContext executa(String texto) {
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ctx.set("input", texto);
        FlowStep step = factory.create(no());
        step.execute(ctx).join();
        return ctx;
    }

    private static boolean aresta(String condicao, ExecutionContext ctx) {
        return new ConditionEvaluator(true).evaluate(condicao, ctx);
    }

    @Test
    @DisplayName("decisão segura segue o caminho automático, lida pelas condições das arestas")
    void caminhoAutomatico() {
        ExecutionContext ctx = executa("o pix não passou, é urgente");

        assertThat(aresta("${triagem.route} == 'AUTO'", ctx)).isTrue();
        assertThat(aresta("${triagem.route} == 'ESCALATE'", ctx)).isFalse();
        assertThat(aresta("${triagem.answers.time.choice} == 'pagamento'", ctx)).isTrue();
        assertThat(aresta("${triagem.answers.time.confidence} >= 0.9", ctx)).isTrue();
        assertThat(aresta("${triagem.decision} == 'pagamento' && ${triagem.route} == 'AUTO'", ctx)).isTrue();
    }

    @Test
    @DisplayName("palpite do 'otherwise' tem confiança 0: a aresta de escalada é a que vale")
    void palpiteEscala() {
        ExecutionContext ctx = executa("bom dia, tudo bem?");

        assertThat(aresta("${triagem.route} == 'ESCALATE'", ctx)).isTrue();
        assertThat(aresta("${triagem.route} == 'AUTO'", ctx)).isFalse();
        assertThat(aresta("${triagem.confidence} < 0.5", ctx)).isTrue();
    }

    @Test
    @DisplayName("provedor que exige chave, sem chave: escala com o motivo, em vez de falhar o fluxo")
    void semChaveEscala() {
        Map<String, Object> http = Map.of("id", "triagem", "type", "decision", "componentId", "decision",
                "config", Map.of("model", "typesafe/jev-1.13",
                        "questions", Map.of("time", Map.of("type", "choice",
                                "criteria", Map.of("a", "", "b", "")))));
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ctx.set("input", "qualquer coisa");

        factory.create(http).execute(ctx).join();

        assertThat(aresta("${triagem.route} == 'ESCALATE'", ctx)).isTrue();
        assertThat(ctx.get("triagem").map(Object::toString).orElse("")).contains("sem chave");
    }

    @Test
    @DisplayName("fora da lista de componentes do fluxo, o nó não resolve — como qualquer outro")
    void respeitaAAllowlist() {
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ctx.set("input", "x");

        var step = factory.create(no(), br.com.archflow.plugin.api.catalog.ComponentAccessPolicy
                .allowOnly(List.of("outro")));
        var resultado = step.execute(ctx).join();

        assertThat(resultado.getStatus()).isEqualTo(br.com.archflow.model.enums.StepStatus.FAILED);
    }

    /**
     * Do nó ao serviço real, pelo OpenRouter: só roda com {@code OPENROUTER_API_KEY} no ambiente. A
     * chave entra pelo resolvedor de chaves, como em produção — nunca pelo nó.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    @DisplayName("[real] o nó decide com o Jev e a rota sai de uma confiança de verdade")
    void servicoReal() {
        DefaultFlowStepFactory comChave = new DefaultFlowStepFactory(
                mock(ComponentCatalog.class), null, null, null, null);
        comChave.setDecisionSupport(br.com.archflow.decision.DecisionProviders.withDefaults(),
                (tenant, ref) -> java.util.Optional.ofNullable(System.getenv("OPENROUTER_API_KEY")));
        Map<String, Object> real = Map.of("id", "triagem", "type", "decision", "componentId", "decision",
                "config", Map.of("model", "typesafe/jev-1.13",
                        "questions", Map.of("time", Map.of("type", "choice",
                                "instructions", "Qual time cuida deste ticket?",
                                "criteria", Map.of("pagamentos", "Cobrança, checkout, reembolso.",
                                        "acesso", "Login, senha, permissões.")))));
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ctx.set("input", "Esqueci minha senha e não consigo entrar na conta.");

        comChave.create(real).execute(ctx).join();

        assertThat(aresta("${triagem.answers.time.choice} == 'acesso'", ctx)).isTrue();
        assertThat(ctx.get("triagem").map(Object::toString).orElse("")).doesNotContain("error=");
    }
}
