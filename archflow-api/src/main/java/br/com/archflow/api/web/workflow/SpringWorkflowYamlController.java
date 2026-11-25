package br.com.archflow.api.web.workflow;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.workflow.InvalidYamlException;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.api.workflow.WorkflowYamlDto;
import br.com.archflow.standalone.model.SerializableFlowConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * YAML view/edit endpoint for the workflow CRUD surface.
 *
 * <p>Reads and writes through {@link WorkflowRuntimeStore} — the
 * same document store the JSON CRUD controller uses — so the "Code" tab
 * in the editor always sees the latest workflow, regardless of whether
 * it was last edited as YAML or via the canvas.
 */
@RestController
@RequestMapping("/api/workflows")
public class SpringWorkflowYamlController {

    private static final TypeReference<LinkedHashMap<String, Object>> DOC_TYPE =
            new TypeReference<>() { };

    private final WorkflowRuntimeStore store;
    private final WorkflowYamlBridge bridge;
    // Local mapper avoids depending on the Spring Boot autoconfigured
    // bean, which isn't always present (the classpath carries both
    // jackson-databind 2.x and 3.x and autoconfig gets confused).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Chaves de {@code configuration} que o YAML representa, tiradas do próprio {@link SerializableFlowConfig}
     * por introspecção: uma lista escrita à mão aqui deixaria de bater no dia em que o POJO ganhasse um campo.
     */
    private final Set<String> yamlConfigurationKeys = objectMapper.getSerializationConfig()
            .introspect(objectMapper.constructType(SerializableFlowConfig.class))
            .findProperties().stream()
            .map(BeanPropertyDefinition::getName)
            .collect(Collectors.toUnmodifiableSet());

    public SpringWorkflowYamlController(WorkflowRuntimeStore store,
                                        WorkflowYamlBridge bridge) {
        this.store = Objects.requireNonNull(store, "store");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @GetMapping("/{id}/yaml")
    public WorkflowYamlDto getYaml(@PathVariable String id) {
        Map<String, Object> doc = store.getWorkflow(TenantContext.currentTenantId(), id);
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
        // Tenant da requisição (RCHFLW-73): o YAML de um fluxo de outro tenant é 404, como no CRUD.
        String tenantId = TenantContext.currentTenantId();
        if (!store.hasWorkflow(tenantId, id)) {
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
        Map<String, Object> existing = store.getWorkflow(tenantId, id);
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(parsed);
        // O putAll acima trocaria 'configuration' inteira pela versão reduzida do YAML, e com ela ia
        // embora o allowedComponents — o fluxo restrito voltava a rodar irrestrito sem aviso (RCHFLW-63).
        merged.put("configuration", mergeConfiguration(existing.get("configuration"), parsed.get("configuration")));
        merged.put("updatedAt", Instant.now().toString());
        merged.putIfAbsent("status", "draft");

        store.putWorkflow(tenantId, id, merged);
        return new WorkflowYamlDto(id, bridge.jsonToYaml(toJson(merged)), extractVersion(merged));
    }

    /**
     * YAML inválido vira {@code {status, error, message, reason, line, column}}. O handler global também daria
     * 400, mas só com a frase; a aba Código precisa da posição, e {@code error} é mantido com a mesma frase para
     * quem já lia o envelope do {@code GlobalExceptionHandler}. {@code message} é a frase pronta (em inglês, para
     * clientes de API); {@code reason} é só o problema, para a UI montar a frase no idioma do usuário com
     * {@code line}/{@code column}. Handler local vence o {@code @RestControllerAdvice}, então isto vale só para
     * as rotas de YAML.
     */
    @ExceptionHandler(InvalidYamlException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidYaml(InvalidYamlException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", e.getMessage());
        body.put("message", e.getMessage());
        body.put("reason", e.getReason());
        // LinkedHashMap e não Map.of: sem posição os campos vão como null, e Map.of rejeita null.
        body.put("line", e.getLine());
        body.put("column", e.getColumn());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * {@code configuration} depois de Salvar YAML: as chaves que o YAML representa vêm do YAML (inclusive
     * a remoção — o usuário pode apagar o {@code allowedComponents} que vê ali); as que ele não representa
     * não têm como ter sido editadas, então ficam com o valor que o documento já tinha.
     */
    private Object mergeConfiguration(Object existing, Object fromYaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (existing instanceof Map<?, ?> previous) {
            previous.forEach((key, value) -> {
                if (!yamlConfigurationKeys.contains(String.valueOf(key))) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        if (fromYaml instanceof Map<?, ?> edited) {
            edited.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        // YAML sem 'configuration': como antes, salvo pelas chaves que ele não representa.
        return result.isEmpty() ? fromYaml : result;
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
