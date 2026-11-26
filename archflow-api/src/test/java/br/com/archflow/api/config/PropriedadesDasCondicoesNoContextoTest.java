package br.com.archflow.api.config;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code archflow.flow.lenient-conditions} (RCHFLW-123) e {@code archflow.flow.strict-conditions} chegam ao bean
 * {@link DefaultFlowStepFactory} pelo contexto do Spring, e não só pelo setter chamado à mão nos testes do motor.
 *
 * <p>O bean é o {@code @Component} de verdade, montado pelo construtor {@code @Autowired} e pelos setters
 * {@code @Value} — um placeholder com o nome errado deixaria a propriedade sem efeito, com o padrão em silêncio.</p>
 */
@DisplayName("propriedades das condições no bean DefaultFlowStepFactory (contexto Spring)")
class PropriedadesDasCondicoesNoContextoTest {

    @Configuration(proxyBeanMethods = false)
    static class Dependencias {

        @Bean
        ComponentCatalog componentCatalog() {
            return mock(ComponentCatalog.class);
        }

        @Bean
        DynamicWorkflowService dynamicWorkflowService() {
            return mock(DynamicWorkflowService.class);
        }

        @Bean
        EventStreamRegistry eventStreamRegistry() {
            return new EventStreamRegistry();
        }

        @Bean
        StateManager stateManager() {
            return new InMemoryStateManager();
        }

        @Bean
        ToolInterceptorChain toolInterceptorChain() {
            return ToolInterceptorChain.builder().build();
        }

        @Bean
        TenantKeyResolver tenantKeyResolver() {
            return mock(TenantKeyResolver.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencias.class, DefaultFlowStepFactory.class);

    /** Uma condition sobre {@code ${pedido.valor}}, que a entrada em texto livre não traz. */
    private static StepResult condicaoSemValor(DefaultFlowStepFactory fabrica) {
        var passo = fabrica.create(Map.of("id", "acima", "type", "condition", "componentId", "condition",
                "label", "Acima de 500?", "configuration", Map.of("conditionExpression", "${pedido.valor} > 500"),
                "connections", List.of()));
        var ctx = new DefaultExecutionContext("t1", "u1", "f",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", "quero reembolso de 900 reais");
        return passo.execute(ctx).join();
    }

    /** Um switch sem caso para o valor, cuja única aresta tem uma condição que não se avalia (valor nulo). */
    private static StepResult switchComArestaNaoAvaliavel(DefaultFlowStepFactory fabrica) {
        var passo = fabrica.create(Map.of("id", "rota", "type", "switch", "componentId", "switch",
                "label", "Por categoria", "configuration", Map.of("switchExpression", "${triagem.decision}",
                        "switchCases", "financeiro"),
                "connections", List.of(Map.of("targetId", "humano", "condition", "${rota.value} > 5"))));
        var ctx = new DefaultExecutionContext("t1", "u1", "f",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        var triagem = new java.util.LinkedHashMap<String, Object>();
        triagem.put("route", "ESCALATE");
        triagem.put("decision", null);
        ctx.set("triagem", triagem);
        return passo.execute(ctx).join();
    }

    @Test
    @DisplayName("sem as propriedades: a referência sem valor falha o passo (falhar fechado é o padrão)")
    void padraoFalhaFechado() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DefaultFlowStepFactory fabrica = ctx.getBean(DefaultFlowStepFactory.class);

            assertThat(condicaoSemValor(fabrica).getStatus()).isEqualTo(StepStatus.FAILED);
            // Motor permissivo (o padrão): a aresta que não se avalia é seguida, e o switch não falha por ela.
            assertThat(switchComArestaNaoAvaliavel(fabrica).getStatus()).isEqualTo(StepStatus.COMPLETED);
        });
    }

    @Test
    @DisplayName("archflow.flow.lenient-conditions=true chega ao bean: a referência sem valor segue o ramo false")
    void lenienteChegaAoBean() {
        runner.withPropertyValues("archflow.flow.lenient-conditions=true").run(ctx -> {
            StepResult r = condicaoSemValor(ctx.getBean(DefaultFlowStepFactory.class));

            assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
            assertThat(String.valueOf(r.getOutput().orElseThrow())).contains("branch=false");
        });
    }

    @Test
    @DisplayName("archflow.flow.strict-conditions=true chega ao bean: o switch confere a saída como o motor estrito")
    void estritoChegaAoBean() {
        runner.withPropertyValues("archflow.flow.strict-conditions=true").run(ctx -> {
            StepResult r = switchComArestaNaoAvaliavel(ctx.getBean(DefaultFlowStepFactory.class));

            assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        });
    }
}
