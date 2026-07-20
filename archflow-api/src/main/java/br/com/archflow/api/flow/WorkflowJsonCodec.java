package br.com.archflow.api.flow;

import br.com.archflow.engine.persistence.jdbc.FlowJsonCodec;
import br.com.archflow.model.flow.Flow;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * {@link FlowJsonCodec} do archflow-api: persiste o documento JSON do designer
 * (retido pelo {@link SimpleFlow#getSourceDocument()}) e reconstrói o flow
 * executável via {@link WorkflowDeserializer} — o mesmo caminho usado na
 * execução, então o round-trip é lossless por construção.
 */
public final class WorkflowJsonCodec implements FlowJsonCodec {

    private final WorkflowDeserializer deserializer;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowJsonCodec(WorkflowDeserializer deserializer) {
        this.deserializer = deserializer;
    }

    @Override
    public String toJson(Flow flow) throws Exception {
        if (flow instanceof SimpleFlow simple && simple.getSourceDocument() != null) {
            return mapper.writeValueAsString(simple.getSourceDocument());
        }
        throw new IllegalArgumentException(
                "Cannot serialize flow " + flow.getId() + " (" + flow.getClass().getSimpleName()
                + "): only SimpleFlow with a source document is supported by this codec");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Flow fromJson(String json) throws Exception {
        Map<String, Object> document = mapper.readValue(json, Map.class);
        return deserializer.toFlow(document);
    }
}
