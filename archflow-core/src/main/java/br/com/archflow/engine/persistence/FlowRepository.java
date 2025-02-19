package br.com.archflow.engine.persistence;

import br.com.archflow.model.flow.Flow;
import java.util.Optional;

public interface FlowRepository {
    void save(Flow flow);
    Optional<Flow> findById(String id);
    void delete(String id);
}