package br.com.archflow.api.web.workflow;

import br.com.archflow.api.config.GlobalExceptionHandler;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.model.tenant.DefaultTenant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * RCHFLW-188, item 13: trocar o {@code targetId} de uma conexão por um passo que não existe e Salvar YAML respondia
 * {@code YAML salvo}; o canvas escondia a aresta (o React Flow não desenha aresta sem ponta) e só a execução falhava,
 * com o fluxo inteiro recusado. Agora o Salvar recusa como o YAML malformado — 400 com o {@code reason} que a aba
 * Código traduz — e o documento gravado fica como estava.
 */
@DisplayName("Salvar YAML — conexão para um passo que não existe")
class YamlComConexaoSemDestinoTest {

    private static final String WF = "wf-yaml-alvo";

    private static String yaml(String alvo) {
        return """
                id: wf-yaml-alvo
                metadata:
                  name: Teste
                  version: 1.0.0
                steps:
                  - id: entrada
                    type: input
                    connections:
                      - sourceId: entrada
                        targetId: %s
                  - id: saida
                    type: output
                """.formatted(alvo);
    }

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryWorkflowRuntimeStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", WF);
        doc.put("metadata", Map.of("name", "Teste", "version", "1.0.0"));
        doc.put("steps", List.of());
        store.putWorkflow(DefaultTenant.ID, WF, doc);
        mvc = MockMvcBuilders.standaloneSetup(new SpringWorkflowYamlController(store, new WorkflowYamlBridge()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MvcResult salvar(String texto) throws Exception {
        return mvc.perform(put("/api/workflows/" + WF + "/yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", WF, "yaml", texto))))
                .andReturn();
    }

    @Test
    @DisplayName("destino inexistente: 400 com o passo e o destino no reason, e nada gravado")
    void destinoInexistenteRecusado() throws Exception {
        MvcResult res = salvar(yaml("saida-6"));

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        JsonNode corpo = json.readTree(res.getResponse().getContentAsString());
        assertThat(corpo.path("reason").asText())
                .isEqualTo("a conexão do passo 'entrada' aponta para um passo que não existe: saida-6");
        assertThat(store.getWorkflow(DefaultTenant.ID, WF).get("steps")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("destino que existe: grava como sempre")
    void destinoExistenteGrava() throws Exception {
        MvcResult res = salvar(yaml("saida"));

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<?>) store.getWorkflow(DefaultTenant.ID, WF).get("steps")).hasSize(2);
    }
}
