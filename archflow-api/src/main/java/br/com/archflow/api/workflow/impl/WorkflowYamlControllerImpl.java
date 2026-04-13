package br.com.archflow.api.workflow.impl;

import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.api.workflow.WorkflowYamlController;
import br.com.archflow.api.workflow.WorkflowYamlDto;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.standalone.model.SerializableFlow;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Framework-agnostic implementation of {@link WorkflowYamlController}.
 *
 * <p>Wires together a {@link FlowRepository} (the product-specific
 * storage backend) with {@link WorkflowYamlBridge} (the stateless
 * YAML↔object converter). Bindings only need to instantiate this class
 * with their own repository and plug it into the REST layer.
 *
 * <p>Path mismatches between the URL id and the YAML body id are
 * rejected so users cannot accidentally overwrite workflow A by pushing
 * workflow B.
 */
public class WorkflowYamlControllerImpl implements WorkflowYamlController {

    private final FlowRepository repository;
    private final WorkflowYamlBridge bridge;

    public WorkflowYamlControllerImpl(FlowRepository repository) {
        this(repository, new WorkflowYamlBridge());
    }

    public WorkflowYamlControllerImpl(FlowRepository repository, WorkflowYamlBridge bridge) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public WorkflowYamlDto getYaml(String id) {
        Objects.requireNonNull(id, "id");
        Flow flow = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Workflow not found: " + id));
        return bridge.toYaml(flow, flow.getMetadata() != null ? flow.getMetadata().version() : null);
    }

    @Override
    public WorkflowYamlDto updateYaml(String id, WorkflowYamlDto request) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");

        SerializableFlow parsed = bridge.fromYaml(request.yaml());

        if (!id.equals(parsed.getId())) {
            throw new IllegalArgumentException(
                    "YAML id '" + parsed.getId() + "' does not match URL id '" + id + "'");
        }

        repository.save(parsed);
        return bridge.toYaml(parsed, parsed.getMetadata() != null ? parsed.getMetadata().version() : null);
    }
}
