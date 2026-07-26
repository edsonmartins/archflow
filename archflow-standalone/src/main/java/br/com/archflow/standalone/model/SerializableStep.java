package br.com.archflow.standalone.model;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Serializable implementation of FlowStep.
 *
 * <p>This DTO is the shared JSON↔YAML round-trip model (see
 * {@code WorkflowYamlBridge}), so it must carry every field the visual
 * designer persists — otherwise editing a flow through the Code/YAML tab
 * silently strips them. The designer's schema is
 * {@code id/type/componentId/label/operation/position/configuration/connections}.
 * Legacy/standalone flows used the {@code config} key; {@link JsonAlias}
 * normalizes it onto the single {@code configuration} field so there are never
 * two config maps that can disagree. Null fields are omitted so
 * designer-authored flows don't accumulate {@code operation: null} noise.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableStep implements FlowStep {

    private String id;
    /**
     * O {@code type} <b>como veio</b>. Ver {@link #setType(String)} — o designer
     * grava aqui o tipo do nó ({@code "llm-chat"}), não o nome de um
     * {@link StepType}.
     */
    private String rawType;
    private StepType type;
    private String componentId;
    /** Display name shown on the canvas node (designer field). */
    private String label;
    private String operation;
    /** Canvas coordinates ({x,y}); preserved so a YAML round-trip keeps the layout. */
    private Map<String, Object> position;
    /** Node configuration. Accepts the legacy {@code config} key on input. */
    @JsonAlias("config")
    private Map<String, Object> configuration;
    private List<SerializableConnection> connections;

    public SerializableStep() {} // Jackson

    public SerializableStep(String id, StepType type, String componentId, String operation,
                             Map<String, Object> config, List<SerializableConnection> connections) {
        this.id = id;
        this.type = type;
        this.rawType = type != null ? type.name() : null;
        this.componentId = componentId;
        this.operation = operation;
        this.configuration = config != null ? Map.copyOf(config) : Map.of();
        this.connections = connections != null ? List.copyOf(connections) : List.of();
    }

    public static SerializableStep from(FlowStep step) {
        List<SerializableConnection> conns = step.getConnections().stream()
                .map(SerializableConnection::from)
                .toList();
        return new SerializableStep(step.getId(), step.getType(), null, null, Map.of(), conns);
    }

    @Override public String getId() { return id; }

    /**
     * O {@link StepType} do contrato {@code FlowStep}.
     *
     * <p>{@code @JsonIgnore} porque o que vai para o JSON é {@link #getTypeAsText()},
     * o tipo cru — senão um {@code llm-chat} do designer voltaria gravado como
     * {@code TOOL} e o roteamento de adapter, que casa pelo tipo do nó, deixaria
     * de reconhecer o passo em silêncio.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override public StepType getType() { return type; }
    @Override @SuppressWarnings("unchecked")
    public List<StepConnection> getConnections() { return (List<StepConnection>) (List<?>) connections; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        // Resolved at runtime by StandaloneRunner via componentId + operation
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Use StandaloneRunner to execute steps"));
    }

    /**
     * Deriva o override de LLM deste passo a partir do {@code config} salvo pela
     * UI (chaves: provider, model, temperature, maxTokens, timeout,
     * additionalConfig). Campos ausentes ficam vazios e são herdados na cadeia
     * de resolução.
     */
    @Override
    public LLMConfigPatch getLLMPatch() {
        return LLMConfigPatch.fromMap(configuration != null ? configuration : Map.of());
    }

    public String getComponentId() { return componentId; }
    public String getLabel() { return label; }
    public String getOperation() { return operation; }
    public Map<String, Object> getPosition() { return position; }
    public Map<String, Object> getConfiguration() { return configuration; }

    public void setId(String id) { this.id = id; }

    /**
     * O {@code type} como aparece no JSON.
     *
     * <p><b>Dois dialetos convivem neste campo.</b> Fluxos escritos à mão e o
     * modelo do standalone usam o nome do enum ({@code "AGENT"}); o designer
     * visual grava o tipo do nó ({@code "input"}, {@code "llm-chat"},
     * {@code "vector-search"}), que é o que o {@code DefaultFlowStepFactory} lê
     * — lá o campo sempre foi string livre.
     *
     * <p>Enquanto isto era um {@code setType(StepType)}, o Jackson recusava o
     * segundo dialeto e <b>todo fluxo feito no designer</b> falhava ao virar
     * YAML ({@code GET /api/workflows/{id}/yaml}) e não podia rodar no
     * standalone. Passava só quem usasse o nome do enum — inclusive os fixtures
     * dos testes, que por isso não pegaram nada.
     *
     * <p>Agora o valor cru é guardado para o transporte e o {@link StepType}
     * é derivado: {@code TOOL} quando não é nome de enum, que é exatamente o que
     * o motor atribui a qualquer passo que não seja ORCHESTRATE nem APPROVAL.
     */
    @com.fasterxml.jackson.annotation.JsonSetter("type")
    public void setType(String type) {
        this.rawType = type;
        this.type = toStepType(type);
    }

    /** Sobrecarga tipada para quem constrói o passo em código. */
    public void setType(StepType type) {
        this.type = type;
        this.rawType = type != null ? type.name() : null;
    }

    /** O {@code type} que vai para o JSON/YAML: o cru, preservado. */
    @com.fasterxml.jackson.annotation.JsonGetter("type")
    public String getTypeAsText() {
        return rawType != null ? rawType : (type != null ? type.name() : null);
    }

    private static StepType toStepType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StepType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notAnEnumName) {
            return StepType.TOOL;
        }
    }
    public void setComponentId(String componentId) { this.componentId = componentId; }
    public void setLabel(String label) { this.label = label; }
    public void setOperation(String operation) { this.operation = operation; }
    public void setPosition(Map<String, Object> position) { this.position = position; }
    public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }
    public void setConnections(List<SerializableConnection> connections) { this.connections = connections; }
}
