package br.com.archflow.agent.persistence;

import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Implementação em memória do FlowRepository
 */
public class InMemoryFlowRepository implements FlowRepository {
    private static final Logger logger = Logger.getLogger(InMemoryFlowRepository.class.getName());
    
    private final Map<String, Flow> flows;

    public InMemoryFlowRepository() {
        this.flows = new ConcurrentHashMap<>();
    }

    @Override
    public void save(Flow flow) {
        logger.info("Salvando fluxo: " + flow.getId());
        flows.put(flow.getId(), flow);
    }

    @Override
    public Optional<Flow> findById(String id) {
        logger.fine("Buscando fluxo: " + id);
        return Optional.ofNullable(flows.get(id));
    }

    @Override
    public void delete(String id) {
        logger.info("Removendo fluxo: " + id);
        flows.remove(id);
    }

    /**
     * Remove todos os fluxos do repositório
     */
    public void clear() {
        flows.clear();
    }
}