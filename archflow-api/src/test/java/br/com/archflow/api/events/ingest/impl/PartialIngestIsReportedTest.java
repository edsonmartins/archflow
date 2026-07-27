package br.com.archflow.api.events.ingest.impl;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.events.ingest.EventIngestController.IngestResultDto;
import br.com.archflow.events.proto.generated.Domain;
import br.com.archflow.events.proto.generated.EventEnvelope;
import br.com.archflow.events.proto.generated.EventType;
import br.com.archflow.events.proto.generated.FlowEvent;
import br.com.archflow.events.proto.generated.FlowEventBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Um lote parcialmente ingerido tem que <b>dizer</b> que foi parcial.
 *
 * <p>A ingestão não é tudo-ou-nada de propósito: um evento malformado no meio
 * do lote não deve derrubar os outros 99, então o endpoint conta os aceitos,
 * conta os rejeitados e devolve <b>HTTP 200</b> de qualquer jeito.
 *
 * <p>O problema era o texto. Qualquer lote com pelo menos um aceito respondia
 * {@code "ok"} — inclusive um em que 40 de 100 falharam. Como sucesso parcial
 * não vira erro HTTP, um cliente que olhasse o status <i>ou</i> a mensagem
 * concluía que estava tudo certo, e os 40 sumiam sem deixar rastro do lado de
 * quem enviou.
 *
 * <p>O servidor já registrava cada falha no log; o que faltava era o remetente
 * conseguir saber. Ele é quem tem o evento original e quem poderia reenviar.
 */
@DisplayName("ingestão parcial é reportada ao cliente")
class PartialIngestIsReportedTest {

    /**
     * Registry que falha ao publicar um evento específico.
     *
     * <p>É o jeito de produzir uma rejeição pelo caminho real: o
     * {@code fromProto} é tolerante — não valida nem lança —, então forçar a
     * falha na desserialização exigiria um protobuf corrompido, que testaria o
     * parser e não a contagem.
     */
    private static final class RegistryQueFalha extends EventStreamRegistry {

        private final String executionIdQueFalha;

        RegistryQueFalha(String executionIdQueFalha) {
            super(60_000, 300_000);
            this.executionIdQueFalha = executionIdQueFalha;
        }

        @Override
        public int broadcast(String executionId, ArchflowEvent event) {
            if (executionIdQueFalha.equals(executionId)) {
                throw new IllegalStateException("falha simulada ao publicar");
            }
            return super.broadcast(executionId, event);
        }
    }

    private static FlowEvent evento(String executionId) {
        return FlowEvent.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setDomain(Domain.DOMAIN_FLOW)
                        .setType(EventType.STEP_STARTED)
                        .setId("id-" + executionId)
                        .setTimestampMillis(1_000L)
                        .setExecutionId(executionId)
                        .build())
                .build();
    }

    private static byte[] lote(String... executionIds) {
        FlowEventBatch.Builder batch = FlowEventBatch.newBuilder()
                .setSourceAgentId("agente-de-teste")
                .setBatchCreatedMillis(1_000L);
        for (String id : executionIds) {
            batch.addEvents(evento(id));
        }
        return batch.build().toByteArray();
    }

    @Test
    @DisplayName("parcial não se anuncia como ok")
    void partialIsNotAnnouncedAsOk() {
        var controller = new EventIngestControllerImpl(new RegistryQueFalha("ruim"));

        IngestResultDto r = controller.ingest(lote("bom", "ruim", "bom2"), null);

        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).isEqualTo(1);
        assertThat(r.message())
                .as("respondia 'ok' com eventos perdidos no lote — o cliente nao tinha como saber")
                .isNotEqualTo("ok")
                .contains("partial")
                .contains("2")
                .contains("1");
    }

    @Test
    @DisplayName("lote inteiramente rejeitado diz isso, e não 'no events processed'")
    void fullyRejectedSaysSo() {
        var controller = new EventIngestControllerImpl(new RegistryQueFalha("ruim"));

        IngestResultDto r = controller.ingest(lote("ruim", "ruim"), null);

        assertThat(r.accepted()).isZero();
        assertThat(r.rejected()).isEqualTo(2);
        assertThat(r.message())
                .as("'no events processed' e o lote VAZIO; aqui vieram 2 e os 2 falharam")
                .contains("rejected")
                .contains("2");
    }

    @Test
    @DisplayName("lote sem falha continua respondendo ok")
    void cleanBatchStillSaysOk() {
        var controller = new EventIngestControllerImpl(new RegistryQueFalha("nao-usado"));

        IngestResultDto r = controller.ingest(lote("a", "b"), null);

        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).isZero();
        assertThat(r.message())
                .as("o caminho feliz nao pode ficar ruidoso, senao a mensagem perde valor")
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("lote vazio continua distinguível de lote todo rejeitado")
    void emptyBatchIsDistinct() {
        var controller = new EventIngestControllerImpl(new RegistryQueFalha("nao-usado"));

        IngestResultDto r = controller.ingest(lote(), null);

        assertThat(r.accepted()).isZero();
        assertThat(r.rejected()).isZero();
        assertThat(r.message()).isEqualTo("no events processed");
    }
}
