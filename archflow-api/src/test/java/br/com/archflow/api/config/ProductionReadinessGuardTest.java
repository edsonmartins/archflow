package br.com.archflow.api.config;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProductionReadinessGuard")
class ProductionReadinessGuardTest {

    private static StandardEnvironment environment(String... activeProfiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(activeProfiles);
        // Auth ligada e segredo JWT próprio por default nos testes: as duas checagens têm teste
        // próprio; aqui o foco são as violações de persistência.
        env.getPropertySources().addLast(new MapPropertySource("auth-on",
                Map.of("archflow.security.auth.enabled", "true",
                        ProductionReadinessGuard.JWT_SECRET_PROPERTY, STRONG_SECRET)));
        return env;
    }

    private static final String STRONG_SECRET = "segredo-do-deployment-aleatorio-com-mais-de-32-bytes";

    /**
     * Ambiente só com o {@code application.yml} real e auth ligada — sem variáveis do sistema, para
     * que um {@code ARCHFLOW_JWT_SECRET} da máquina não esconda o default do arquivo.
     */
    private static StandardEnvironment environmentFromApplicationYml(String... activeProfiles) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.setActiveProfiles(activeProfiles);
        env.getPropertySources().addFirst(new MapPropertySource("auth-on",
                Map.of("archflow.security.auth.enabled", "true")));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }

    @Test
    @DisplayName("segredo JWT default do application.yml fora de dev/test veta o boot: token ADMIN forjável")
    void defaultJwtSecretFromApplicationYmlFailsBoot() throws Exception {
        StandardEnvironment env = environmentFromApplicationYml("staging");
        // allow-in-memory NÃO excusa um segredo público
        env.getPropertySources().addFirst(new MapPropertySource("escape",
                Map.of(ProductionReadinessGuard.ALLOW_IN_MEMORY_PROPERTY, "true")));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, new DefaultListableBeanFactory());

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archflow.security.jwt.secret")
                .hasMessageContaining("ARCHFLOW_JWT_SECRET");
    }

    @Test
    @DisplayName("sem SPRING_PROFILES_ACTIVE o application.yml ativa dev: o ambiente local segue subindo")
    void withoutProfileApplicationYmlActivatesDev() throws Exception {
        StandardEnvironment env = environmentFromApplicationYml();

        assertThat(env.getActiveProfiles()).containsExactly("dev");
        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, new DefaultListableBeanFactory());
        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SPRING_PROFILES_ACTIVE=staging com o application.yml real veta o segredo JWT default")
    void stagingFromEnvironmentFailsBoot() throws Exception {
        StandardEnvironment env = environmentFromApplicationYml();
        env.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of("SPRING_PROFILES_ACTIVE", "staging")));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, new DefaultListableBeanFactory());

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHFLOW_JWT_SECRET");
    }

    @Test
    @DisplayName("property ausente (cai no default do @Value do JwtService) veta o boot")
    void missingJwtSecretFailsBoot() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("auth-on",
                Map.of("archflow.security.auth.enabled", "true")));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, new DefaultListableBeanFactory());

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archflow.security.jwt.secret");
    }

    @Test
    @DisplayName("o default que o @Value do bean JwtService usa também é público")
    void beanDefaultJwtSecretFailsBoot() {
        StandardEnvironment env = environment();
        env.getPropertySources().addFirst(new MapPropertySource("secret",
                Map.of(ProductionReadinessGuard.JWT_SECRET_PROPERTY,
                        "change-me-in-production-this-must-be-very-long")));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, new DefaultListableBeanFactory());

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archflow.security.jwt.secret");
    }

    @Test
    @DisplayName("ARCHFLOW_JWT_SECRET definido sobre o application.yml passa")
    void ownJwtSecretPasses() throws Exception {
        StandardEnvironment env = environmentFromApplicationYml("staging");
        env.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of("ARCHFLOW_JWT_SECRET", STRONG_SECRET)));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, new DefaultListableBeanFactory());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev profile aceita o segredo JWT default (ambiente local)")
    void devProfileAcceptsDefaultJwtSecret() throws Exception {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                environmentFromApplicationYml("dev"), new DefaultListableBeanFactory());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("auth desligada fora de dev/test veta o boot (sem escape hatch)")
    void authDisabledOutsideDevFailsBoot() {
        StandardEnvironment env = new StandardEnvironment();
        // allow-in-memory NÃO excusa auth desligada
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of(ProductionReadinessGuard.ALLOW_IN_MEMORY_PROPERTY, "true")));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(
                env, new DefaultListableBeanFactory());

        org.assertj.core.api.Assertions.assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archflow.security.auth.enabled");
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
    @DisplayName("revogação de tokens em memória fora de dev/test veta o boot: o logout valeria numa réplica só")
    void inMemoryTokenBlacklistFailsProduction() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("tokenBlacklist", new br.com.archflow.security.auth.InMemoryTokenBlacklist());

        ProductionReadinessGuard guard = new ProductionReadinessGuard(environment(), factory);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InMemoryTokenBlacklist");
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

    @Test
    @DisplayName("hash embeddings vetam produção mesmo com escape hatch de stores")
    void hashEmbeddingFailsProduction() {
        StandardEnvironment env = environment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of(ProductionReadinessGuard.ALLOW_IN_MEMORY_PROPERTY, "true")));
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("knowledgeEmbeddingModel",
                new br.com.archflow.api.knowledge.HashEmbeddingModel(128));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(env, factory);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HashEmbeddingModel")
                .hasMessageContaining("OPENAI_API_KEY");
    }
}
