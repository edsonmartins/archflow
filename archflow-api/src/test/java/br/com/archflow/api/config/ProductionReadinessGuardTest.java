package br.com.archflow.api.config;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProductionReadinessGuard")
class ProductionReadinessGuardTest {

    private static StandardEnvironment environment(String... activeProfiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(activeProfiles);
        return env;
    }

    private static DefaultListableBeanFactory factoryWithInMemoryFlowRepository() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("flowRepository", new InMemoryFlowRepository());
        return factory;
    }

    /** Durable-looking FlowRepository (anything that is not the in-memory class). */
    private static FlowRepository durableFlowRepository() {
        return new FlowRepository() {
            @Override public void save(Flow flow) {}
            @Override public Optional<Flow> findById(String id) { return Optional.empty(); }
            @Override public void delete(String id) {}
        };
    }

    @Test
    @DisplayName("fails startup when in-memory store is active without dev profile")
    void failsInProduction() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                environment(), factoryWithInMemoryFlowRepository());

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InMemoryFlowRepository")
                .hasMessageContaining("archflow.allow-in-memory");
    }

    @Test
    @DisplayName("dev profile skips the check entirely")
    void devProfileSkips() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                environment("dev"), factoryWithInMemoryFlowRepository());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("test profile skips the check entirely")
    void testProfileSkips() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                environment("test"), factoryWithInMemoryFlowRepository());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("archflow.allow-in-memory=true downgrades the failure to a warning")
    void allowInMemoryEscapeHatch() {
        StandardEnvironment env = environment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of(ProductionReadinessGuard.ALLOW_IN_MEMORY_PROPERTY, "true")));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                env, factoryWithInMemoryFlowRepository());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("durable implementations pass in production")
    void durableImplementationsPass() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("flowRepository", durableFlowRepository());

        ProductionReadinessGuard guard = new ProductionReadinessGuard(environment(), factory);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("empty context passes (no stores wired at all)")
    void emptyContextPasses() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                environment(), new DefaultListableBeanFactory());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}
