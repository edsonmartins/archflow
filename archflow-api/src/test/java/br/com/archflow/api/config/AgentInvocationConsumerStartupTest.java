package br.com.archflow.api.config;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.invocation.AgentInvocationConsumer;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O consumidor liga no fim do refresh, depois de todos os singletons — entre eles o Flyway. Ligado como
 * {@code initMethod}, o despachante reivindicava durante a criação dos beans: num banco ainda na V6_12, o
 * claim batia numa tabela sem {@code claimed_by}, e invocações remanescentes rodavam antes de a aplicação
 * estar pronta.
 */
@DisplayName("AgentInvocationConfiguration — o consumidor só liga depois das migrations")
class AgentInvocationConsumerStartupTest {

    static final AtomicBoolean migrated = new AtomicBoolean();
    static final AtomicBoolean claimedBeforeMigration = new AtomicBoolean();
    static final CountDownLatch claimed = new CountDownLatch(1);

    /** Fila, catálogo e um bean lento no lugar do Flyway — declarado depois do consumidor. */
    @Configuration
    static class Infra {
        @Bean
        AgentInvocationQueue agentInvocationQueue() {
            return new InMemoryAgentInvocationQueue() {
                @Override
                public Optional<InvocationRequest> claim(String consumerId, Duration lease) {
                    if (!migrated.get()) {
                        claimedBeforeMigration.set(true);
                    }
                    claimed.countDown();
                    return super.claim(consumerId, lease);
                }
            };
        }

        @Bean
        ComponentCatalog componentCatalog() {
            return mock(ComponentCatalog.class);
        }

        @Bean
        InitializingBean flywayStandIn() {
            return () -> {
                Thread.sleep(300);
                migrated.set(true);
            };
        }
    }

    @Test
    @DisplayName("nenhum claim antes das migrations; ligado e reivindicando depois do refresh")
    void startsOnlyAfterRefresh() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("teste",
                    Map.of("archflow.agents.invocation.poll-interval", "PT0.01S")));
            // A ordem de registro é a ordem de criação: o consumidor nasce antes do "Flyway".
            ctx.register(AgentInvocationConfiguration.class, Infra.class);
            ctx.refresh();

            assertThat(claimed.await(5, TimeUnit.SECONDS)).as("o consumidor nunca reivindicou").isTrue();
            assertThat(claimedBeforeMigration.get()).as("reivindicou antes das migrations").isFalse();
            assertThat(ctx.getBean(AgentInvocationConsumer.class).isRunning()).isTrue();
        }
    }
}
