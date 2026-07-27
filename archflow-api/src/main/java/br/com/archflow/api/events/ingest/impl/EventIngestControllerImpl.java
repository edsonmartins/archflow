package br.com.archflow.api.events.ingest.impl;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.events.ingest.EventIngestController;
import br.com.archflow.events.proto.ProtobufEventMapper;
import br.com.archflow.events.proto.generated.FlowEventBatch;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Framework-agnostic implementation of {@link EventIngestController}.
 *
 * <p>For each {@link FlowEventBatch} received:
 * <ol>
 *   <li>Parse the raw bytes into a protobuf message.</li>
 *   <li>Map each {@code FlowEvent} → {@link ArchflowEvent}.</li>
 *   <li>Pin the tenant: override {@code tenantId} with the authenticated
 *       caller's tenant to prevent spoofing.</li>
 *   <li>Broadcast to {@code executionId} + {@code "__admin__:" + tenantId}.</li>
 * </ol>
 *
 * <p>Invalid protobuf bodies throw {@link IllegalArgumentException} which
 * the binding layer should map to HTTP 400.
 */
public class EventIngestControllerImpl implements EventIngestController {

    private static final Logger log = Logger.getLogger(EventIngestControllerImpl.class.getName());

    private final EventStreamRegistry registry;

    public EventIngestControllerImpl(EventStreamRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public IngestResultDto ingest(byte[] protobufBody, String authenticatedTenantId) {
        FlowEventBatch batch;
        try {
            batch = FlowEventBatch.parseFrom(protobufBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid protobuf FlowEventBatch: " + e.getMessage(), e);
        }

        int accepted = 0;
        int rejected = 0;

        for (var protoEvent : batch.getEventsList()) {
            try {
                ArchflowEvent event = ProtobufEventMapper.fromProto(protoEvent);

                // Tenant pinning — rebuild the event with the authenticated tenant
                // to prevent a rogue standalone agent from spoofing other tenants
                if (authenticatedTenantId != null) {
                    event = ArchflowEvent.builder(event.getEnvelope())
                            .tenantId(authenticatedTenantId)
                            .data(event.getData())
                            .metadata(event.getMetadata())
                            .build();
                }

                // Broadcast to flow-specific channel
                String executionId = event.getExecutionId();
                if (executionId != null && !executionId.isBlank()) {
                    registry.broadcast(executionId, event);
                }

                // Broadcast to admin channel
                if (authenticatedTenantId != null) {
                    registry.broadcast("__admin__:" + authenticatedTenantId, event);
                }

                accepted++;
            } catch (Exception e) {
                log.warning("Failed to ingest event: " + e.getMessage());
                rejected++;
            }
        }

        return new IngestResultDto(accepted, rejected, describe(accepted, rejected));
    }

    /**
     * Descreve o resultado do lote — inclusive quando ele foi <b>parcial</b>.
     *
     * <p>Antes, qualquer lote com pelo menos um evento aceito respondia
     * {@code "ok"}. Um lote de 100 em que 40 falharam devolvia
     * {@code {"accepted":60,"rejected":40,"message":"ok"}} com HTTP 200 — e
     * como sucesso parcial não vira erro HTTP, um cliente que olhasse o status
     * ou a mensagem concluía que estava tudo certo. Os 40 sumiam.
     *
     * <p>O status continua 200 de propósito: rejeitar o lote inteiro por causa
     * de um evento malformado perderia os 60 que estavam bons, e a ingestão de
     * telemetria não deve ser tudo-ou-nada. O que muda é que a resposta passa a
     * dizer o que aconteceu.
     */
    private static String describe(int accepted, int rejected) {
        if (rejected == 0) {
            return accepted > 0 ? "ok" : "no events processed";
        }
        if (accepted == 0) {
            return "all " + rejected + " event(s) rejected";
        }
        return "partial: " + accepted + " accepted, " + rejected + " rejected";
    }
}
