package br.com.archflow.api.agent.invocation;

import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;

/** Peças comuns aos testes do consumidor: catálogo real e espera pelo resultado final. */
final class InvocationTestSupport {

    private InvocationTestSupport() {
    }

    /** O catálogo que o servidor monta (embutidos inicializados), sem diretório de plugins. */
    static ComponentCatalog realCatalog() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        return new ArchflowBeanConfiguration().componentCatalog(semPlugins);
    }

    static AgentInvocationConsumer.Settings fastSettings(int maxConcurrency) {
        return new AgentInvocationConsumer.Settings(maxConcurrency, Duration.ofMillis(20),
                Duration.ofMinutes(5), 3, Duration.ofSeconds(5));
    }

    /** Espera o resultado ficar final; falha com o último estado visto, em vez de travar o teste. */
    static AgentInvocationResult awaitTerminal(AgentInvocationResultStore store, String requestId) {
        return await(() -> store.find(requestId).filter(r -> r.status().terminal()),
                () -> "invocação " + requestId + " não terminou; último estado: " + store.find(requestId));
    }

    static <T> T await(Supplier<Optional<T>> probe, Supplier<String> onTimeout) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrompido", e);
            }
        }
        throw new AssertionError(onTimeout.get());
    }
}
