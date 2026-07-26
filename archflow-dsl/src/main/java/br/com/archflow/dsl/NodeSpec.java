package br.com.archflow.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * O que um passo <i>é</i> — tipo, componente e configuração — sem ainda dizer
 * onde ele está no grafo. As arestas ficam no {@link WorkflowBuilder}, porque
 * um passo não é dono de para onde vai.
 *
 * <p>Imutável: cada {@code with} devolve uma nova instância, então uma
 * {@code NodeSpec} pode virar constante e ser reusada em vários fluxos sem que
 * um contamine o outro.
 *
 * <p>Construída pelas fábricas de {@link Nodes}.
 *
 * @since 1.1.0
 */
public final class NodeSpec {

    private final String type;
    private final String componentId;
    private final String operation;
    private final String label;
    private final Map<String, Object> config;

    NodeSpec(String type, String componentId) {
        this(type, componentId, null, null, Map.of());
    }

    private NodeSpec(String type, String componentId, String operation, String label,
                     Map<String, Object> config) {
        this.type = type;
        this.componentId = componentId;
        this.operation = operation;
        this.label = label;
        this.config = config;
    }

    /**
     * Uma chave de configuração. O motor entrega este mapa ao componente como
     * veio; a DSL não interpreta o conteúdo.
     */
    public NodeSpec with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> merged = new LinkedHashMap<>(config);
        merged.put(key, value);
        return new NodeSpec(type, componentId, operation, label,
                Collections.unmodifiableMap(merged));
    }

    /** Várias chaves de uma vez; as existentes com o mesmo nome são substituídas. */
    public NodeSpec with(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(config);
        merged.putAll(values);
        return new NodeSpec(type, componentId, operation, label,
                Collections.unmodifiableMap(merged));
    }

    /**
     * Operação invocada no componente. Default {@code execute} — o mesmo
     * default do motor, então omitir aqui e omitir no JSON dão no mesmo.
     */
    public NodeSpec operation(String operation) {
        return new NodeSpec(type, componentId, operation, label, config);
    }

    /**
     * Nome de exibição do nó no canvas. Puramente visual: o motor nunca o lê
     * (ele lê {@code operation}), mas o designer o mostra, então preenchê-lo
     * faz um fluxo escrito em código abrir legível na tela.
     */
    public NodeSpec labeled(String label) {
        return new NodeSpec(type, componentId, operation, label, config);
    }

    String type() {
        return type;
    }

    String componentId() {
        return componentId;
    }

    String operation() {
        return operation;
    }

    String label() {
        return label;
    }

    Map<String, Object> config() {
        return config;
    }
}
