package br.com.archflow.api.events.ingest.impl;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.events.ingest.EventIngestController.IngestResultDto;
import br.com.archflow.events.proto.ProtobufEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@code executionId} do evento vem do cliente e é a chave do canal: um agente do tenant A não
 * pode usá-lo para entregar eventos no chat ou no canal administrativo do tenant B. Fixar o
 * {@code tenantId} do evento não basta — quem decide quem recebe é a chave.
 *
 * <p>A prova é o que chega nos assinantes reais do registry, não o contador da resposta.
 */
@DisplayName("Ingest não escreve no canal de outro tenant")
class IngestNaoEscreveNoCanalDeOutroTenantTest {

    private EventStreamRegistry registry;
    private EventIngestControllerImpl controller;

    @BeforeEach
    void monta() {
        registry = new EventStreamRegistry(60_000, 300_000);
        controller = new EventIngestControllerImpl(registry);
    }

    @AfterEach
    void desmonta() {
        registry.shutdown();
    }

    private List<ArchflowEvent> assina(String tenant, String sessao) {
        List<ArchflowEvent> recebidos = new ArrayList<>();
        registry.createEmitter(tenant, sessao).onSend(recebidos::add);
        return recebidos;
    }

    private IngestResultDto publica(String executionId, String tenantAutenticado) {
        ArchflowEvent evento = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_COMPLETED)
                .executionId(executionId)
                .build();
        byte[] corpo = ProtobufEventMapper.toBatch(List.of(evento), "agente").toByteArray();
        return controller.ingest(corpo, tenantAutenticado);
    }

    @Test
    @DisplayName("agente do tenant A endereçando o chat de B → recusado, B não recebe")
    void chatDeOutroTenant() {
        List<ArchflowEvent> chatDeB = assina("tenant-b", "conv-1");

        IngestResultDto resultado = publica("tenant-b:conv-1", "tenant-a");

        assertThat(chatDeB).isEmpty();
        assertThat(resultado.rejected()).isEqualTo(1);
        assertThat(resultado.accepted()).isZero();
    }

    @Test
    @DisplayName("agente do tenant A endereçando o canal admin de B → recusado, B não recebe")
    void canalAdminDeOutroTenant() {
        List<ArchflowEvent> adminDeB = assina("__admin__", "tenant-b");

        IngestResultDto resultado = publica("__admin__:tenant-b", "tenant-a");

        assertThat(adminDeB).isEmpty();
        assertThat(resultado.rejected()).isEqualTo(1);
    }

    /**
     * O canal admin é alimentado pelo servidor; o cliente não o endereça, nem quem personifica o id
     * reservado (o prefixo "{@code __admin__:}" então coincidiria com "{@code <tenant>:}").
     */
    @Test
    @DisplayName("tenant efetivo \"__admin__\" também não endereça o canal admin de outro")
    void idReservadoComoTenant() {
        List<ArchflowEvent> adminDeB = assina("__admin__", "tenant-b");

        publica("__admin__:tenant-b", "__admin__");

        assertThat(adminDeB).isEmpty();
    }

    @Test
    @DisplayName("sem tenant resolvido, um id em forma de canal não é entregue")
    void semTenantNaoEntregaEmCanal() {
        List<ArchflowEvent> chatDeB = assina("tenant-b", "conv-1");

        publica("tenant-b:conv-1", null);

        assertThat(chatDeB).isEmpty();
    }

    @Test
    @DisplayName("o próprio canal e um id de execução comum continuam sendo entregues")
    void proprioCanalEIdComumSeguem() {
        List<ArchflowEvent> chatDeA = assina("tenant-a", "conv-1");
        List<ArchflowEvent> execucao = new ArrayList<>();
        registry.createEmitter("exec-42").onSend(execucao::add);

        IngestResultDto doCanal = publica("tenant-a:conv-1", "tenant-a");
        IngestResultDto comum = publica("exec-42", "tenant-a");

        assertThat(chatDeA).hasSize(1);
        assertThat(execucao).hasSize(1);
        assertThat(doCanal.accepted()).isEqualTo(1);
        assertThat(comum.accepted()).isEqualTo(1);
    }
}
