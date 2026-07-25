package br.com.archflow.api.config;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.FlowStepFactory;
import br.com.archflow.api.flow.HumanApprovalStep;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O step APPROVAL precisa do {@link FlowEngine} para suspender o fluxo, mas no
 * perfil JDBC (produção) o grafo de beans é
 * {@code FlowEngine → FlowRepository → WorkflowDeserializer → FlowStepFactory}:
 * injetar o engine direto na factory fecharia o ciclo e o contexto não subiria.
 *
 * <p>Este teste reproduz exatamente essa forma e garante que a resolução tardia
 * via {@link ObjectProvider} a mantém aberta — uma regressão aqui só apareceria
 * no boot da aplicação real.
 */
@DisplayName("Grafo de beans do step APPROVAL")
class ApprovalStepBeanGraphTest {

    @Configuration(proxyBeanMethods = false)
    static class JdbcShapedConfiguration {

        @Bean
        StateManager stateManager() {
            return new InMemoryStateManager();
        }

        @Bean
        FlowStepFactory flowStepFactory(StateManager stateManager,
                                        ObjectProvider<FlowEngine> flowEngine) {
            return new DefaultFlowStepFactory(
                    mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                    new EventStreamRegistry(), stateManager, flowEngine);
        }

        @Bean
        WorkflowDeserializer workflowDeserializer(FlowStepFactory stepFactory) {
            return new DefaultWorkflowDeserializer(stepFactory);
        }

        /** Como no perfil JDBC: o repositório depende do desserializador. */
        @Bean
        FlowRepository flowRepository(WorkflowDeserializer workflowDeserializer) {
            return new InMemoryFlowRepository();
        }

        @Bean
        FlowEngine flowEngine(FlowRepository flowRepository, StateManager stateManager) {
            return br.com.archflow.api.flow.FlowEngineFactory.create(
                    flowRepository, null, null, stateManager);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JdbcShapedConfiguration.class);

    @Test
    @DisplayName("o contexto sobe: a resolução tardia quebra o ciclo")
    void contextStartsWithoutCircularDependency() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FlowEngine.class);
            assertThat(ctx).hasSingleBean(FlowStepFactory.class);
        });
    }

    @Test
    @DisplayName("a factory materializa um step APPROVAL ligado ao engine do contexto")
    void buildsApprovalStepBoundToTheEngine() {
        runner.run(ctx -> {
            FlowStep step = ctx.getBean(FlowStepFactory.class)
                    .create(Map.of("id", "gate", "type", "APPROVAL"));

            assertThat(step).isInstanceOf(HumanApprovalStep.class);
            assertThat(step.getType()).isEqualTo(StepType.APPROVAL);
        });
    }
}
