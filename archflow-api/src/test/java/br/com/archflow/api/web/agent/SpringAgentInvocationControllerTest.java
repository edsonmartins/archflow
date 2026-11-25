package br.com.archflow.api.web.agent;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.invocation.AgentInvocationLookup;
import br.com.archflow.api.agent.invocation.AgentInvocationResult;
import br.com.archflow.api.agent.invocation.InMemoryAgentInvocationResultStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-80: o Agent lab consulta {@code GET /archflow/agents/invocations/{requestId}} depois de
 * Invocar/Enviar. A consulta devolve o resultado gravado ou o andamento, e trata um requestId de outro
 * tenant como inexistente — o mesmo 404 de um id desconhecido.
 */
@DisplayName("GET /archflow/agents/invocations/{requestId}")
class SpringAgentInvocationControllerTest {

    private InMemoryAgentInvocationQueue queue;
    private InMemoryAgentInvocationResultStore store;
    private SpringAgentInvocationController controller;

    @BeforeEach
    void setUp() {
        queue = new InMemoryAgentInvocationQueue();
        store = new InMemoryAgentInvocationResultStore();
        controller = new SpringAgentInvocationController(new AgentInvocationLookup(store, queue, null));
    }

    @Test
    @DisplayName("resultado gravado → 200 com status, saída e horários")
    void recordedResult() {
        InvocationRequest r = InvocationRequest.root("tenant-a", "text-transform-tool", Map.of());
        Instant inicio = Instant.parse("2026-11-24T10:00:00Z");
        store.save(AgentInvocationResult.running(r, 1, inicio).completed("OLÁ", inicio.plusSeconds(1)));

        ResponseEntity<?> resposta = controller.get(r.requestId(), "tenant-a");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        AgentInvocationResult body = (AgentInvocationResult) resposta.getBody();
        assertThat(body.status()).isEqualTo(AgentInvocationResult.Status.COMPLETED);
        assertThat(body.output()).isEqualTo("OLÁ");
        assertThat(body.startedAt()).isEqualTo(inicio);
        assertThat(body.finishedAt()).isEqualTo(inicio.plusSeconds(1));
    }

    @Test
    @DisplayName("ainda na fila → 200 QUEUED")
    void queued() {
        InvocationRequest r = InvocationRequest.root("tenant-a", "a", Map.of());
        queue.submit(r);

        ResponseEntity<?> resposta = controller.get(r.requestId(), "tenant-a");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((AgentInvocationResult) resposta.getBody()).status())
                .isEqualTo(AgentInvocationResult.Status.QUEUED);
    }

    @Test
    @DisplayName("requestId de outro tenant → 404, igual a um id desconhecido")
    void otherTenantIsNotFound() {
        InvocationRequest gravada = InvocationRequest.root("tenant-a", "a", Map.of());
        store.save(AgentInvocationResult.running(gravada, 1, Instant.now()).failed("boom", Instant.now()));
        InvocationRequest naFila = InvocationRequest.root("tenant-a", "a", Map.of());
        queue.submit(naFila);

        assertThat(controller.get(gravada.requestId(), "tenant-b").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get(naFila.requestId(), "tenant-b").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get("nao-existe", "tenant-a").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
