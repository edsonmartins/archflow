package br.com.archflow.api.flow;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the stored workflow JSON ({@code { id, metadata, steps, configuration }})
 * into an executable {@link SimpleFlow}, delegating per-node construction to the
 * {@link FlowStepFactory} (design-0004 step 1).
 */
@Component
public class DefaultWorkflowDeserializer implements WorkflowDeserializer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWorkflowDeserializer.class);

    private final FlowStepFactory stepFactory;
    private final boolean requireAllowlist;

    public DefaultWorkflowDeserializer(FlowStepFactory stepFactory) {
        this(stepFactory, false);
    }

    /**
     * @param requireAllowlist quando {@code true}, um fluxo sem
     *                         {@code configuration.allowedComponents} não resolve
     *                         componente nenhum (fail-closed). Default
     *                         {@code false} — ver {@link #componentPolicyOf}.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultWorkflowDeserializer(
            FlowStepFactory stepFactory,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.components.require-allowlist:false}") boolean requireAllowlist) {
        this.stepFactory = stepFactory;
        this.requireAllowlist = requireAllowlist;
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

        ComponentAccessPolicy componentPolicy = componentPolicyOf(json);

        List<FlowStep> steps = asList(json.get("steps")).stream()
                .map(DefaultWorkflowDeserializer::asMap)
                .map(node -> stepFactory.create(node, componentPolicy))
                .toList();

        return new SimpleFlow(str(json.get("id"), ""), metadata, steps, json);
    }

    /**
     * Allowlist de componentes declarada pelo fluxo em
     * {@code configuration.allowedComponents}.
     *
     * <p>Ausente significa <b>sem restrição</b> por default, e não "nada
     * permitido": fechar isso globalmente quebraria todo workflow já salvo e o
     * designer, cuja liberdade de escolher qualquer componente é o ponto. Um
     * fluxo que precise da garantia — "este nunca toca um componente de escrita"
     * — declara a lista.
     *
     * <p>Um deployment que queira a garantia para <b>todos</b> os fluxos liga
     * {@code archflow.components.require-allowlist=true}: aí a ausência passa a
     * negar tudo, e um fluxo sem lista falha alto (todo step reporta
     * "component not found") em vez de rodar irrestrito. É opt-in porque a
     * escolha é de produto: quem liga precisa ter migrado os fluxos antes.
     */
    private ComponentAccessPolicy componentPolicyOf(Map<String, Object> json) {
        Object declared = asMap(json.get("configuration")).get("allowedComponents");
        if (!(declared instanceof List<?> ids)) {
            if (requireAllowlist) {
                logger.warn("Fluxo {} não declara configuration.allowedComponents e "
                                + "archflow.components.require-allowlist=true — nenhum componente será resolvido",
                        str(json.get("id"), "?"));
                return ComponentAccessPolicy.allowOnly(List.of());
            }
            return ComponentAccessPolicy.allowAll();
        }
        List<String> allowed = ids.stream().filter(Objects::nonNull).map(Object::toString).toList();
        logger.info("Fluxo {} restrito a {} componente(s): {}",
                str(json.get("id"), "?"), allowed.size(), allowed);
        return ComponentAccessPolicy.allowOnly(allowed);
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
