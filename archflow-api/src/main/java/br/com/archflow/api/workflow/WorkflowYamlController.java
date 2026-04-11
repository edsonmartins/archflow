package br.com.archflow.api.workflow;

/**
 * REST contract for YAML round-trip of a workflow.
 *
 * <p>Endpoints (base path {@code /api/workflows/{id}}):
 * <ul>
 *   <li>{@code GET  /yaml}  → returns the workflow serialized as YAML text.</li>
 *   <li>{@code PUT  /yaml}  → replaces the stored workflow using the YAML body.</li>
 * </ul>
 *
 * <p>Binding layer (Spring Boot, Jetty, ...) is responsible for:
 * <ul>
 *   <li>Resolving the {@code id} path param.</li>
 *   <li>Loading/persisting the workflow via the product-specific repository.</li>
 *   <li>Delegating the actual (de)serialization to
 *       {@link br.com.archflow.standalone.YamlFlowSerializer} (or an injected
 *       strategy).</li>
 *   <li>Enforcing authentication + tenant isolation.</li>
 * </ul>
 *
 * <p>Rationale: keeping the YAML endpoint on a separate path rather than
 * content negotiation (Accept: application/x-yaml) keeps browser behavior
 * predictable — the UI can {@code GET} raw text and push updates with a
 * simple {@code PUT} from the code editor without extra headers.
 */
public interface WorkflowYamlController {

    /**
     * Returns the workflow {@code id} serialized as a YAML string.
     * Implementations should set {@code Content-Type: application/x-yaml}
     * on the response.
     */
    WorkflowYamlDto getYaml(String id);

    /**
     * Replaces the workflow identified by {@code id} with the contents of
     * {@code request.yaml()}. Implementations validate the YAML before
     * persisting and return the updated payload.
     *
     * @throws IllegalArgumentException when the YAML is malformed
     */
    WorkflowYamlDto updateYaml(String id, WorkflowYamlDto request);
}
