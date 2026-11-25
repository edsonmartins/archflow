package br.com.archflow.api.web.workflow;

import br.com.archflow.model.tenant.DefaultTenant;

import br.com.archflow.api.config.GlobalExceptionHandler;
import br.com.archflow.api.workflow.InvalidYamlException;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * RCHFLW-94: "Salvar YAML" com texto inválido mostrava na aba Código o corpo cru da resposta — a mensagem do
 * SnakeYAML com o trecho do texto e as quebras de linha, e no erro de mapeamento a cadeia de referências do
 * Jackson com classes Java ({@code SerializableFlow["steps"]->java.util.ArrayList[0]}). O contrato agora é
 * {@code 400 {status, error, message, reason, line, column}}: frase de uma linha, posição 1-based, nada de Java.
 */
@DisplayName("YAML inválido — forma do erro")
class WorkflowYamlErrorShapeTest {

    /** O exemplo do ticket: {@code steps:} trocado por um flow node que nunca fecha. */
    private static final String YAML_QUEBRADO = """
            id: wf-1
            metadata:
              name: Teste
              version: 1.0.0
            steps: [ isto nao e yaml: {
            """;

    /** Sintaxe válida, estrutura errada: {@code steps} é texto, não lista — o caso da cadeia de referências. */
    private static final String YAML_COM_TIPO_ERRADO = """
            id: wf-1
            steps:
              - id: s1
                type: llm-chat
                connections: "nao e lista"
            """;

    /**
     * Sem {@code version} em {@code metadata}: o FlowMetadata rejeita no construtor e o Jackson embrulha o
     * IllegalArgumentException num ValueInstantiationException. A frase do modelo é o que diz o que falta.
     */
    private static final String YAML_SEM_VERSAO = """
            id: wf-1
            metadata:
              name: Teste
            steps: []
            """;

    private static final List<String> VAZAMENTOS = List.of(
            "br.com.", "java.", "reference chain", "in 'reader'", "Malformed", "Cannot deserialize", "`");

    private final WorkflowYamlBridge bridge = new WorkflowYamlBridge();
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InMemoryWorkflowRuntimeStore store = new InMemoryWorkflowRuntimeStore();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "wf-1");
        doc.put("metadata", Map.of("name", "Teste", "version", "1.0.0"));
        doc.put("steps", List.of());
        store.putWorkflow(DefaultTenant.ID, "wf-1", doc);
        mvc = MockMvcBuilders.standaloneSetup(new SpringWorkflowYamlController(store, bridge))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("erro de sintaxe: frase de uma linha com linha/coluna, sem o dump do SnakeYAML")
    void sintaxe() {
        InvalidYamlException e = catchThrowableOfType(InvalidYamlException.class,
                () -> bridge.fromYaml(YAML_QUEBRADO));

        assertThat(e.getLine()).isNotNull().isBetween(5, 6);
        assertThat(e.getColumn()).isNotNull().isPositive();
        assertThat(e.getMessage())
                .startsWith("Invalid YAML at line " + e.getLine() + ", column " + e.getColumn() + ": ")
                .doesNotContain("\n");
        assertThat(VAZAMENTOS).noneMatch(e.getMessage()::contains);
    }

    @Test
    @DisplayName("erro de mapeamento: nomeia o campo como no YAML, sem tipos Java nem cadeia de referências")
    void mapeamento() {
        InvalidYamlException e = catchThrowableOfType(InvalidYamlException.class,
                () -> bridge.yamlToJson(YAML_COM_TIPO_ERRADO));

        assertThat(e.getMessage())
                .contains("'steps[0].connections'")
                .contains("must be a list")
                .doesNotContain("\n");
        assertThat(e.getLine()).isEqualTo(5);
        assertThat(VAZAMENTOS).noneMatch(e.getMessage()::contains);
    }

    @Test
    @DisplayName("valor recusado pelo modelo: a frase de domínio chega ao usuário, não só 'invalid value'")
    void validacaoDoModelo() {
        InvalidYamlException e = catchThrowableOfType(InvalidYamlException.class,
                () -> bridge.fromYaml(YAML_SEM_VERSAO));

        assertThat(e.getReason()).isEqualTo("field 'metadata': Version is required");
        assertThat(e.getMessage()).endsWith(": field 'metadata': Version is required").doesNotContain("\n");
        assertThat(VAZAMENTOS).noneMatch(e.getMessage()::contains);
    }

    @Test
    @DisplayName("PUT /yaml inválido responde 400 {status, error, message, reason, line, column}")
    void respostaHttp() throws Exception {
        MvcResult res = mvc.perform(put("/api/workflows/wf-1/yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", "wf-1", "yaml", YAML_QUEBRADO))))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        JsonNode corpo = json.readTree(res.getResponse().getContentAsString());
        assertThat(corpo.path("status").asInt()).isEqualTo(400);
        assertThat(corpo.path("line").isInt()).isTrue();
        assertThat(corpo.path("column").isInt()).isTrue();
        String mensagem = corpo.path("message").asText();
        assertThat(mensagem).startsWith("Invalid YAML at line " + corpo.path("line").asInt() + ", column ");
        // `error` carrega a mesma frase: é o campo que o envelope do GlobalExceptionHandler sempre teve.
        assertThat(corpo.path("error").asText()).isEqualTo(mensagem);
        // `reason` é o problema sem prefixo nem posição: a UI monta a frase traduzida em volta dele.
        String motivo = corpo.path("reason").asText();
        assertThat(motivo).isNotBlank().doesNotStartWith("Invalid").doesNotContain("line ");
        assertThat(mensagem).endsWith(": " + motivo);
        assertThat(VAZAMENTOS).noneMatch(res.getResponse().getContentAsString()::contains);
    }
}
