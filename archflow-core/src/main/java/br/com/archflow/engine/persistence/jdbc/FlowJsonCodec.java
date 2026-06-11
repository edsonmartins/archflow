package br.com.archflow.engine.persistence.jdbc;

import br.com.archflow.model.flow.Flow;

/**
 * Converts {@link Flow} instances to/from JSON for relational storage.
 *
 * <p>{@code Flow} is an interface whose concrete implementations live in the
 * layers above the engine (e.g. {@code SerializableFlow} in archflow-standalone,
 * {@code SimpleFlow} in archflow-api), so the JDBC repository cannot pick a
 * deserialization target itself — callers supply the codec for the flow
 * representation they use.
 */
public interface FlowJsonCodec {

    /** Serializes a flow definition to JSON. */
    String toJson(Flow flow) throws Exception;

    /** Reconstructs a flow definition from JSON. */
    Flow fromJson(String json) throws Exception;
}
