package br.com.archflow.api.flow;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns the stored workflow JSON ({@code { id, metadata, steps, configuration }})
 * into an executable {@link SimpleFlow}, delegating per-node construction to the
 * {@link FlowStepFactory} (design-0004 step 1).
 */
@Component
public class DefaultWorkflowDeserializer implements WorkflowDeserializer {

    private final FlowStepFactory stepFactory;

    public DefaultWorkflowDeserializer(FlowStepFactory stepFactory) {
        this.stepFactory = stepFactory;
    }

    @Override
    public Flow toFlow(Map<String, Object> json) {
        Map<String, Object> meta = asMap(json.get("metadata"));
        FlowMetadata metadata = new FlowMetadata(
                str(meta.get("name"), "Untitled"),
                str(meta.get("description"), ""),
                str(meta.get("version"), "1.0.0"),
                str(meta.get("author"), null),
                str(meta.get("category"), null),
                List.of());

        List<FlowStep> steps = asList(json.get("steps")).stream()
                .map(DefaultWorkflowDeserializer::asMap)
                .map(stepFactory::create)
                .toList();

        return new SimpleFlow(str(json.get("id"), ""), metadata, steps);
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
