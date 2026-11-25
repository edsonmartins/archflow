package br.com.archflow.api.flow;

import br.com.archflow.model.flow.FlowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diz quais tipos de nó de um documento de workflow o motor deste deployment não executa.
 *
 * <p><b>Por que existe.</b> Os templates (os 4 SPI e os do pacote da UI) usam tipos de nó que não
 * têm executor — {@code vector-search} sem provider, {@code sentiment}, {@code reranker},
 * {@code agent}... O clone abre no designer normalmente e só na execução cada um falha com
 * "component not found". A galeria usa esta lista para marcar o template em vez de esconder o
 * problema (RCHFLW-18, opção A).
 *
 * <p><b>Não há uma segunda regra aqui.</b> Em vez de repetir a lista de tipos que o
 * {@link DefaultFlowStepFactory} reconhece (controle, aprovação, orquestração, mcp-agent, decisão,
 * adapters LangChain4j, catálogo), cada nó é construído pelo próprio factory do runtime: o que vira
 * um passo dedicado roda; um {@link ComponentStep} roda se tiver componente. Quando o motor ganhar
 * um tipo novo, esta resposta muda junto — uma lista copiada ficaria para trás em silêncio.
 */
@Component
public class NodeSupport {

    private static final Logger log = LoggerFactory.getLogger(NodeSupport.class);

    private final FlowStepFactory stepFactory;

    public NodeSupport(FlowStepFactory stepFactory) {
        this.stepFactory = stepFactory;
    }

    /**
     * Os tipos de nó sem executor, sem repetição e na ordem em que aparecem.
     *
     * @param steps os {@code steps[]} do documento (forma canônica: {@code type},
     *              {@code componentId}, {@code configuration})
     * @return lista vazia quando todos os nós executam
     */
    public List<String> unsupportedNodeTypes(List<? extends Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Set<String> unsupported = new LinkedHashSet<>();
        for (Map<String, Object> node : steps) {
            if (node == null) {
                continue;
            }
            FlowStep step;
            try {
                step = stepFactory.create(node);
            } catch (RuntimeException e) {
                // O factory só lança ao inicializar um passo dedicado (mcp-agent, decisão) com
                // config inválida: o tipo existe no motor, o problema é a config — não é o que esta
                // lista responde.
                log.debug("Nó {} não construiu: {}", node.get("id"), e.getMessage());
                continue;
            }
            // Tipo da paleta sem execução (RCHFLW-11) ou componente que o catálogo não tem.
            if (step instanceof UnsupportedNodeStep
                    || step instanceof ComponentStep component && !component.resolvesComponent()) {
                unsupported.add(typeOf(node));
            }
        }
        return new ArrayList<>(unsupported);
    }

    /** O nome que o usuário reconhece: o componentId, ou o type quando não há um. */
    private static String typeOf(Map<String, Object> node) {
        Object raw = node.get("componentId");
        if (raw == null || raw.toString().isBlank()) {
            raw = node.get("type");
        }
        return raw == null ? "" : raw.toString().trim().toLowerCase();
    }
}
