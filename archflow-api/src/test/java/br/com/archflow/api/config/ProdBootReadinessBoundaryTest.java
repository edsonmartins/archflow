package br.com.archflow.api.config;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.persistence.RepositoryStateManager;
import br.com.archflow.engine.persistence.jdbc.JdbcStateRepository;
import br.com.archflow.observability.audit.JdbcAuditRepository;
import br.com.archflow.security.apikey.JdbcApiKeyRepository;
import br.com.archflow.security.auth.JdbcUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Smoke test da <b>fronteira de prod-readiness</b>: monta o conjunto de beans que
 * um boot do {@code archflow-api} em perfil prod (jdbc.enabled + DataSource) teria
 * — os stores duráveis JDBC ligados por {@code JdbcPersistenceConfiguration} mais
 * os dois defaults in-memory que o framework <b>não</b> auto-liga como duráveis —
 * e roda o {@link ProductionReadinessGuard} contra ele.
 *
 * <p>Documenta, com asserção, o resultado exato da leva de persistência durável
 * (#4/#5/#6 + Flyway + flag prod): ela limpou seis stores, mas um boot prod ainda
 * é <b>barrado pelo guard</b> em dois que precisam de wiring adicional:
 * <ul>
 *   <li>{@code FlowRepository} — o {@code JdbcFlowRepository} existe mas precisa de
 *       um {@code FlowJsonCodec} específico do deployment;</li>
 *   <li>{@code AgentInvocationQueue} — não há implementação durável.</li>
 * </ul>
 * Quando esses dois forem cobertos, {@link #withDurableFlowAndQueue_guardPasses()}
 * já prova que o guard fica verde — este teste então deve ser atualizado.
 *
 * <p>Sem Docker: o guard só inspeciona o <i>tipo</i> de cada bean, então os repos
 * JDBC são instanciados com um DataSource stub (só guardam a referência no ctor).
 */
@DisplayName("Fronteira de prod-readiness (guard sobre o conjunto de beans do perfil prod)")
class ProdBootReadinessBoundaryTest {

    private static final String QUARTZ_DELEGATE = "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate";

    /** Ambiente sem profile dev/test → o guard roda de verdade (como em prod). */
    private static StandardEnvironment prodLikeEnvironment() {
        StandardEnvironment env = new StandardEnvironment();
        // Como no perfil prod real: auth ligada (o guard recusa boot com auth
        // desligada fora de dev/test — checagem separada das de persistência).
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test", java.util.Map.of("archflow.security.auth.enabled", "true")));
        return env;
    }

    /**
     * Beans duráveis que {@code JdbcPersistenceConfiguration} liga sob a flag — os
     * stores que a leva de persistência tornou duráveis (incluindo o
     * {@code JdbcWorkflowRuntimeStore} do runtime do designer). Flow e Queue ficam
     * a cargo do chamador (o ponto do teste).
     */
    private static DefaultListableBeanFactory durableStoresFactory() throws Exception {
        return durableStoresFactory(
                new br.com.archflow.api.web.workflow.JdbcWorkflowRuntimeStore(new StubDataSource()));
    }

    private static DefaultListableBeanFactory durableStoresFactory(
            br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowRuntimeStore) throws Exception {
        DataSource ds = new StubDataSource();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("stateManager",
                new RepositoryStateManager(new JdbcStateRepository(ds)));
        factory.registerSingleton("userRepository", new JdbcUserRepository(ds));
        factory.registerSingleton("apiKeyRepository", new JdbcApiKeyRepository(ds));
        factory.registerSingleton("auditRepository", new JdbcAuditRepository(ds));
        factory.registerSingleton("suspendedConversationStore",
                new br.com.archflow.conversation.persistence.jdbc.JdbcSuspendedConversationStore(ds));
        factory.registerSingleton("workflowRuntimeStore", workflowRuntimeStore);
        // Quartz JDBCJobStore (JobStoreTX), criado sem conectar ao banco.
        factory.registerSingleton("quartzScheduler",
                DurableQuartzScheduler.create(ds, QUARTZ_DELEGATE, "archflow-triggers-smoke"));
        return factory;
    }

    @Test
    @DisplayName("boot prod: guard barra EXATAMENTE FlowRepository e AgentInvocationQueue (os demais são duráveis)")
    void prodBeansMinusFlowAndQueue_guardFailsCitingExactlyThose() throws Exception {
        DefaultListableBeanFactory factory = durableStoresFactory();
        factory.registerSingleton("flowRepository", new InMemoryFlowRepository());
        factory.registerSingleton("agentInvocationQueue", new InMemoryAgentInvocationQueue());

        ProductionReadinessGuard guard = new ProductionReadinessGuard(prodLikeEnvironment(), factory);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                // os dois blockers remanescentes
                .hasMessageContaining("InMemoryFlowRepository")
                .hasMessageContaining("InMemoryAgentInvocationQueue")
                // os seis stores da leva de persistência NÃO aparecem como violação
                .matches(e -> !e.getMessage().contains("InMemoryStateManager"), "StateManager durável")
                .matches(e -> !e.getMessage().contains("InMemoryUserRepository"), "UserRepository durável")
                .matches(e -> !e.getMessage().contains("InMemoryApiKeyRepository"), "ApiKeyRepository durável")
                .matches(e -> !e.getMessage().contains("InMemoryAuditRepository"), "AuditRepository durável")
                .matches(e -> !e.getMessage().contains("InMemorySuspendedConversationStore"),
                        "SuspendedConversationStore durável")
                .matches(e -> !e.getMessage().contains("InMemoryWorkflowRuntimeStore"),
                        "WorkflowRuntimeStore durável")
                .matches(e -> !e.getMessage().contains("RAMJobStore"), "Quartz durável");
    }

    @Test
    @DisplayName("runtime store de workflows in-memory é blocker: guard cita InMemoryWorkflowRuntimeStore")
    void inMemoryWorkflowRuntimeStore_guardFailsCitingIt() throws Exception {
        DefaultListableBeanFactory factory = durableStoresFactory(
                new br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore());
        // Flow e Queue duráveis (como no cenário verde) — isola o runtime store
        // como a única violação.
        DataSource ds = new StubDataSource();
        factory.registerSingleton("flowRepository",
                new br.com.archflow.engine.persistence.jdbc.JdbcFlowRepository(ds,
                        new br.com.archflow.api.flow.WorkflowJsonCodec(mock(
                                br.com.archflow.api.flow.WorkflowDeserializer.class))));
        factory.registerSingleton("agentInvocationQueue",
                new br.com.archflow.api.queue.JdbcAgentInvocationQueue(ds));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(prodLikeEnvironment(), factory);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InMemoryWorkflowRuntimeStore")
                .hasMessageContaining("WorkflowRuntimeStore — workflows e execuções do designer");
    }

    @Test
    @DisplayName("com Flow e Queue duráveis, o guard fica verde — provam ser os únicos blockers")
    void withDurableFlowAndQueue_guardPasses() throws Exception {
        DefaultListableBeanFactory factory = durableStoresFactory();
        // As implementações duráveis REAIS que o JdbcPersistenceConfiguration
        // liga em prod (construtores só guardam referências).
        DataSource ds = new StubDataSource();
        factory.registerSingleton("flowRepository",
                new br.com.archflow.engine.persistence.jdbc.JdbcFlowRepository(ds,
                        new br.com.archflow.api.flow.WorkflowJsonCodec(mock(
                                br.com.archflow.api.flow.WorkflowDeserializer.class))));
        factory.registerSingleton("agentInvocationQueue",
                new br.com.archflow.api.queue.JdbcAgentInvocationQueue(ds));

        ProductionReadinessGuard guard = new ProductionReadinessGuard(prodLikeEnvironment(), factory);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    /** DataSource stub — os repositórios JDBC só guardam a referência no ctor. */
    private static final class StubDataSource implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String u, String p) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
