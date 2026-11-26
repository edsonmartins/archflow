package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RCHFLW-125: com o fluxo aberto no editor, outra sessão gravava uma mudança pelo mesmo PUT; o Salvar do editor
 * respondia "Fluxo salvo com sucesso" e apagava a mudança da outra sessão em silêncio. Agora o editor manda o
 * {@code updatedAt} que leu no {@code If-Match}: se o fluxo mudou desde então, 409 e nada é gravado.
 *
 * <p>Por HTTP (MockMvc), e não chamando o método: o que importa é o nome do cabeçalho que o Spring liga ao
 * parâmetro. Quem não manda versão (VendaX, scripts) grava como sempre gravou.
 */
@DisplayName("PUT do fluxo com a versão lida (RCHFLW-125)")
class SalvarComVersaoLidaTest {

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryWorkflowRuntimeStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        var controller = new SpringWorkflowCrudController(store, mock(WorkflowDeserializer.class),
                mock(FlowEngine.class), mock(FlowRepository.class), null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("versão antiga no If-Match → 409 com o updatedAt atual, e a gravação da outra sessão fica")
    void versaoAntigaDaConflito() throws Exception {
        Map<String, Object> lido = cria("Fluxo");
        String versaoLida = String.valueOf(lido.get("updatedAt"));

        // A outra sessão grava primeiro (sem versão, como um script).
        Map<String, Object> outra = corpo(grava(put("/api/workflows/" + lido.get("id")), documento("Outra aba")));
        assertThat(outra.get("updatedAt")).isNotEqualTo(versaoLida);

        MvcResult conflito = mvc.perform(json(put("/api/workflows/" + lido.get("id"))
                        .header("If-Match", "\"" + versaoLida + "\""), documento("Editor")))
                .andReturn();

        assertThat(conflito.getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> erro = corpo(conflito);
        assertThat(erro.get("code")).isEqualTo("WORKFLOW_CONFLICT");
        assertThat(erro.get("updatedAt")).isEqualTo(outra.get("updatedAt"));
        assertThat(nome(store.getWorkflow(null, String.valueOf(lido.get("id"))))).isEqualTo("Outra aba");
    }

    @Test
    @DisplayName("versão atual no If-Match (com ou sem aspas) → grava e devolve um updatedAt novo")
    void versaoAtualGrava() throws Exception {
        Map<String, Object> lido = cria("Fluxo");

        Map<String, Object> salvo = corpo(grava(put("/api/workflows/" + lido.get("id"))
                .header("If-Match", "\"" + lido.get("updatedAt") + "\""), documento("Editor")));
        assertThat(nome(salvo)).isEqualTo("Editor");
        assertThat(salvo.get("updatedAt")).isNotEqualTo(lido.get("updatedAt"));

        // O updatedAt devolvido é o que vale para o próximo Salvar, também sem aspas.
        Map<String, Object> deNovo = corpo(grava(put("/api/workflows/" + lido.get("id"))
                .header("If-Match", String.valueOf(salvo.get("updatedAt"))), documento("Editor 2")));
        assertThat(nome(deNovo)).isEqualTo("Editor 2");
    }

    @Test
    @DisplayName("sem If-Match (ou com *) → grava como antes, mesmo depois de outra gravação")
    void semVersaoGravaComoAntes() throws Exception {
        Map<String, Object> lido = cria("Fluxo");
        grava(put("/api/workflows/" + lido.get("id")), documento("Primeira"));

        Map<String, Object> semVersao = corpo(grava(put("/api/workflows/" + lido.get("id")), documento("Script")));
        assertThat(nome(semVersao)).isEqualTo("Script");
        Map<String, Object> qualquer = corpo(grava(put("/api/workflows/" + lido.get("id")).header("If-Match", "*"),
                documento("Qualquer")));
        assertThat(nome(qualquer)).isEqualTo("Qualquer");
    }

    @Test
    @DisplayName("fluxo inexistente continua 404, com ou sem versão")
    void inexistenteContinua404() throws Exception {
        assertThat(mvc.perform(json(put("/api/workflows/wf-nada").header("If-Match", "\"x\""), documento("X")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // ── helpers ──────────────────────────────────────────────────

    private Map<String, Object> cria(String nome) throws Exception {
        corpo(grava(post("/api/workflows"), documento(nome)));
        // Relê pelo GET, como o editor: é desse documento que ele tira a versão.
        String id = store.workflows(null).iterator().next().get("id").toString();
        return corpo(mvc.perform(get("/api/workflows/" + id)).andReturn());
    }

    private MvcResult grava(MockHttpServletRequestBuilder pedido, Map<String, Object> doc) throws Exception {
        MvcResult resultado = mvc.perform(json(pedido, doc)).andReturn();
        assertThat(resultado.getResponse().getStatus()).isBetween(200, 201);
        return resultado;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder pedido, Map<String, Object> doc)
            throws Exception {
        return pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(doc));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> corpo(MvcResult resultado) throws Exception {
        return json.readValue(resultado.getResponse().getContentAsString(), Map.class);
    }

    private static Map<String, Object> documento(String nome) {
        return Map.of("metadata", Map.of("name", nome), "steps", java.util.List.of(), "configuration", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static String nome(Map<String, Object> doc) {
        return String.valueOf(((Map<String, Object>) doc.get("metadata")).get("name"));
    }
}
