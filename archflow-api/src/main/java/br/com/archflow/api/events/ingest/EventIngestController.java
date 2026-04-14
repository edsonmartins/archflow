package br.com.archflow.api.events.ingest;

/**
 * REST contract for the protobuf event ingestion endpoint.
 *
 * <p>Binding layer note: map the method to
 * {@code POST /api/events/ingest} with
 * {@code Content-Type: application/x-protobuf}.
 *
 * <p>Authentication and tenant resolution happen in the binding layer
 * (e.g., a Spring {@code @RequestMapping} that reads the JWT / API key
 * and resolves {@code authenticatedTenantId}).
 */
public interface EventIngestController {

    /**
     * Accepts a protobuf-serialized {@code FlowEventBatch} body and
     * broadcasts each contained event into the in-process
     * {@link br.com.archflow.agent.streaming.EventStreamRegistry}.
     *
     * <p>Tenant pinning: the {@code authenticatedTenantId} from the
     * caller's credentials OVERRIDES any tenant claim in the batch.
     * This prevents cross-tenant spoofing from standalone agents.
     *
     * @param protobufBody          raw bytes of the serialized
     *                              {@code FlowEventBatch}
     * @param authenticatedTenantId tenant resolved from the caller's
     *                              credentials; must not be null
     * @return a short summary of the ingest operation
     * @throws IllegalArgumentException if the body cannot be parsed as a
     *                                  valid {@code FlowEventBatch}
     */
    IngestResultDto ingest(byte[] protobufBody, String authenticatedTenantId);

    /**
     * Result returned after a successful (or partially successful) ingest.
     *
     * @param accepted number of events successfully broadcast
     * @param rejected number of events that could not be processed
     * @param message  optional informational message (may be empty)
     */
    record IngestResultDto(int accepted, int rejected, String message) {}
}
