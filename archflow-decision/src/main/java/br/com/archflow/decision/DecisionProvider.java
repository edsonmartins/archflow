package br.com.archflow.decision;

import java.util.Map;
import java.util.Optional;

/**
 * Quem responde a decisões. A interface é o ponto de extensão: um modelo de decisão por HTTP, um
 * LLM usado como classificador, um conjunto de regras, um modelo local — todos entram aqui, e o
 * componente não sabe qual é.
 *
 * <p>Provedores de plugin são descobertos por {@link java.util.ServiceLoader}
 * ({@code META-INF/services/br.com.archflow.decision.DecisionProvider}); o hospedeiro pode ainda
 * registrar os seus em {@link DecisionProviders#register}.</p>
 */
public interface DecisionProvider {

    /** O nome pelo qual o nó o escolhe ({@code provider: "…"}). */
    String id();

    /**
     * A referência da chave de API deste provedor, para o hospedeiro resolvê-la por tenant; vazio
     * quando o provedor não usa chave. {@code options} deixa o nó apontar outra referência.
     */
    default Optional<String> keyRef(Map<String, Object> options) {
        return Optional.empty();
    }

    /** O provedor exige chave? Sem ela a chamada nem é feita, e a decisão escala. */
    default boolean requiresKey() {
        return false;
    }

    DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException;
}
