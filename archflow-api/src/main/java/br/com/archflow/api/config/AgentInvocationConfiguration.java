package br.com.archflow.api.config;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.api.agent.invocation.AgentInvocationConsumer;
import br.com.archflow.api.agent.invocation.AgentInvocationLookup;
import br.com.archflow.api.agent.invocation.AgentInvocationResultStore;
import br.com.archflow.api.agent.invocation.CatalogAgentInvocationExecutor;
import br.com.archflow.api.agent.invocation.InMemoryAgentInvocationResultStore;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * O consumidor da fila de invocações de agente e a consulta do resultado (RCHFLW-80).
 *
 * <p>Num arquivo próprio, e não no {@code ArchflowBeanConfiguration}, para que a fila ganhe um consumidor
 * sem mexer na fiação dos produtores (invoke, chat, gatilhos, eventos): eles continuam só enfileirando, e
 * tudo o que acontece depois mora aqui.</p>
 *
 * <p>O consumidor liga por padrão — a fila sem consumidor era o defeito. {@code
 * archflow.agents.invocation.consumer.enabled=false} o desliga numa réplica que só deve aceitar
 * invocações (a fila JDBC é compartilhada; outra réplica as executa). O store em memória segue a mesma
 * regra determinística da fila em memória (propriedade, não ordem de beans): com JDBC ligado, quem o
 * declara é o {@code JdbcPersistenceConfiguration}.</p>
 *
 * <p>Sem {@code initMethod}: o consumidor é um {@code SmartLifecycle} e o Spring o liga no fim do refresh,
 * depois do Flyway — ligado antes, reivindicava contra uma tabela ainda não migrada.</p>
 */
@Configuration
public class AgentInvocationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ArchflowBeanConfiguration.JDBC_ENABLED, havingValue = "false",
            matchIfMissing = true)
    public AgentInvocationResultStore agentInvocationResultStore() {
        return new InMemoryAgentInvocationResultStore();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.agents.invocation.consumer.enabled", havingValue = "true",
            matchIfMissing = true)
    public AgentInvocationConsumer agentInvocationConsumer(
            AgentInvocationQueue agentInvocationQueue,
            AgentInvocationResultStore agentInvocationResultStore,
            ComponentCatalog componentCatalog,
            ObjectProvider<McpAgentHost> mcpAgentHost,
            @Value("${archflow.agents.invocation.max-concurrency:8}") int maxConcurrency,
            @Value("${archflow.agents.invocation.poll-interval:PT0.5S}") Duration pollInterval,
            @Value("${archflow.agents.invocation.lease:PT5M}") Duration lease,
            @Value("${archflow.agents.invocation.max-attempts:3}") int maxAttempts,
            @Value("${archflow.agents.invocation.shutdown-grace:PT10S}") Duration shutdownGrace,
            @Value("${archflow.agents.invocation.timeout:PT10M}") Duration executionTimeout,
            @Value("${archflow.agents.invocation.result-retention:P7D}") Duration resultRetention) {
        return new AgentInvocationConsumer(agentInvocationQueue, agentInvocationResultStore,
                new CatalogAgentInvocationExecutor(componentCatalog, mcpAgentHost.getIfAvailable()),
                new AgentInvocationConsumer.Settings(maxConcurrency, pollInterval, lease, maxAttempts,
                        shutdownGrace, executionTimeout, resultRetention));
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentInvocationLookup agentInvocationLookup(
            AgentInvocationResultStore agentInvocationResultStore,
            AgentInvocationQueue agentInvocationQueue,
            ObjectProvider<AgentInvocationConsumer> agentInvocationConsumer) {
        return new AgentInvocationLookup(agentInvocationResultStore, agentInvocationQueue,
                agentInvocationConsumer.getIfAvailable());
    }
}
