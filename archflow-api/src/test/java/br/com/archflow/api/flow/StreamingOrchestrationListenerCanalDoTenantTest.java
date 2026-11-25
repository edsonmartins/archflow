package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-50: os eventos da orquestração (nó {@code orchestrate}) seguem a regra dos eventos de fluxo —
 * também no canal {@code tenantId:executionId}, o único de execução que o stream SSE deixa assinar.
 */
@DisplayName("StreamingOrchestrationListener — canal do tenant da execução")
class StreamingOrchestrationListenerCanalDoTenantTest {

    private final EventStreamRegistry registry = new EventStreamRegistry(60_000, 300_000);

    @AfterEach
    void desmonta() {
        registry.shutdown();
    }

    @Test
    @DisplayName("plano e convergência chegam a tenant:execução, e não ao mesmo id em outro tenant")
    void entregaNoCanalDoTenant() {
        var doTenant = new CopyOnWriteArrayList<ArchflowEvent>();
        var deOutro = new CopyOnWriteArrayList<ArchflowEvent>();
        registry.createEmitter("tenant-a", "exec-1").onSend(doTenant::add);
        registry.createEmitter("tenant-b", "exec-1").onSend(deOutro::add);

        var listener = new StreamingOrchestrationListener(registry, "tenant-a", "exec-1");
        listener.onPlanned(1, List.of("a", "b"));
        listener.onConverged(1, 2);

        assertThat(doTenant).hasSize(2);
        assertThat(doTenant).extracting(ArchflowEvent::getTenantId).containsOnly("tenant-a");
        assertThat(deOutro).isEmpty();
    }

    @Test
    @DisplayName("os ouvintes globais recebem cada evento uma vez")
    void globaisUmaVez() {
        var globais = new CopyOnWriteArrayList<ArchflowEvent>();
        registry.addGlobalListener(globais::add);

        new StreamingOrchestrationListener(registry, "tenant-a", "exec-1").onRoundCompleted(1, true, 1);

        assertThat(globais).hasSize(1);
    }

    @Test
    @DisplayName("sem tenant (construtor de dois argumentos): só o canal nu, nenhum canal de tenant")
    void semTenantSoOCanalNu() {
        var doTenant = new CopyOnWriteArrayList<ArchflowEvent>();
        var globais = new CopyOnWriteArrayList<ArchflowEvent>();
        registry.createEmitter("tenant-a", "exec-1").onSend(doTenant::add);
        registry.addGlobalListener(globais::add);

        new StreamingOrchestrationListener(registry, "exec-1").onPlanned(1, List.of("a"));

        assertThat(globais).hasSize(1);
        assertThat(doTenant).isEmpty();
    }
}
