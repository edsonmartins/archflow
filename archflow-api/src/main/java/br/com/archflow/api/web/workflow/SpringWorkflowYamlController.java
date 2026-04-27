package br.com.archflow.api.web.workflow;

import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.api.workflow.WorkflowYamlDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * YAML view/edit endpoint for the workflow CRUD surface.
 *
 * <p>Reads and writes through {@link InMemoryWorkflowRuntimeStore} — the
 * same document store the JSON CRUD controller uses — so the "Code" tab
 * in the editor always sees the latest workflow, regardless of whether
 * it was last edited as YAML or via the canvas.
 */
@RestController
@RequestMapping("/api/workflows")
public class SpringWorkflowYamlController {

    private static final TypeReference<LinkedHashMap<String, Object>> DOC_TYPE =
            new TypeReference<>() { };

    private final InMemoryWorkflowRuntimeStore store;
    private final WorkflowYamlBridge bridge;
    // Local mapper avoids depending on the Spring Boot autoconfigured
    // bean, which isn't always present (the classpath carries both
    // jackson-databind 2.x and 3.x and autoconfig gets confused).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SpringWorkflowYamlController(InMemoryWorkflowRuntimeStore store,
                                        WorkflowYamlBridge bridge) {
        this.store = Objects.requireNonNull(store, "store");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @GetMapping("/{id}/yaml")
    public WorkflowYamlDto getYaml(@PathVariable String id) {
        Map<String, Object> doc = store.getWorkflow(id);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found: " + id);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize workflow", e);
        }
        String yaml = bridge.jsonToYaml(json);
        String version = extractVersion(doc);
        return new WorkflowYamlDto(id, yaml, version);
    }

    @PutMapping("/{id}/yaml")
    public WorkflowYamlDto updateYaml(@PathVariable String id, @RequestBody WorkflowYamlDto request) {
        if (request == null || request.yaml() == null || request.yaml().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "YAML payload is empty");
        }
        if (!store.hasWorkflow(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found: " + id);
        }

        String json = bridge.yamlToJson(request.yaml());
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(json, DOC_TYPE);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to parse YAML payload: " + e.getMessage(), e);
        }
        Object parsedId = parsed.get("id");
        if (parsedId != null && !id.equals(parsedId.toString())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "YAML id '" + parsedId + "' does not match URL id '" + id + "'");
        }
        parsed.put("id", id);

        // Preserve UI-only metadata the YAML representation doesn't carry
        // (status, updatedAt). Anything the YAML body provides wins; the
        // existing values fill the gaps.
        Map<String, Object> existing = store.getWorkflow(id);
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(parsed);
        merged.put("updatedAt", Instant.now().toString());
        merged.putIfAbsent("status", "draft");

        store.putWorkflow(id, merged);
        return new WorkflowYamlDto(id, bridge.jsonToYaml(toJson(merged)), extractVersion(merged));
    }

    private String toJson(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize workflow", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractVersion(Map<String, Object> doc) {
        Object meta = doc.get("metadata");
        if (meta instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("version");
            if (v != null) return v.toString();
        }
        return null;
    }
}
